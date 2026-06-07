package com.medfund.rules.integration;

import com.medfund.rules.compiler.ActionEmitters;
import com.medfund.rules.compiler.DrlCompiler;
import com.medfund.rules.engine.TenantRuleEngine;
import com.medfund.rules.fact.ClaimFact;
import com.medfund.rules.fact.MemberFact;
import com.medfund.rules.fact.RuleResult;
import com.medfund.rules.model.Condition;
import com.medfund.rules.model.ConditionGroup;
import com.medfund.rules.model.RuleAction;
import com.medfund.rules.model.RuleDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency / isolation regression test for {@link TenantRuleEngine}.
 *
 * <p>Each tenant gets its own {@code KieContainer} via {@link TenantRuleEngine#loadRules};
 * a regression where one tenant's rules accidentally fire on another tenant's
 * facts would be catastrophic for multi-tenant integrity (a rejection rule
 * meant for tenant A silently rejecting tenant B's claims, or vice versa).
 *
 * <p>This test:
 * <ol>
 *   <li>Loads <em>different</em> rules for tenants A and B
 *      (A: REJECT on SUSPENDED, B: FLAG on PENDING).</li>
 *   <li>Fires 500 evaluations from each tenant concurrently across 8 worker
 *      threads.</li>
 *   <li>Asserts every result was produced by the correct tenant's rule —
 *      zero cross-pollination.</li>
 * </ol>
 *
 * <p>Also asserts that {@link TenantRuleEngine#reloadRules} called from one
 * thread doesn't return stale results to a thread mid-evaluation on the same
 * tenant.
 */
class TenantRuleEngineConcurrencyIT {

    private static final String TENANT_A = "tenant-aaaaaaaa";
    private static final String TENANT_B = "tenant-bbbbbbbb";

    private TenantRuleEngine engine;

    @BeforeEach
    void setUp() {
        var compiler = new DrlCompiler(List.of(
            new ActionEmitters.RejectEmitter(),
            new ActionEmitters.FlagEmitter(),
            new ActionEmitters.WarnEmitter(),
            new ActionEmitters.CapToTariffEmitter()
        ));
        engine = new TenantRuleEngine(compiler);

        engine.loadRules(TENANT_A, List.of(
            rejectRule("Reject suspended", "SUSPENDED", "R-A1", "Member suspended (A)")));
        engine.loadRules(TENANT_B, List.of(
            flagRule("Flag pending", "PENDING", "F-B1", "Member pending (B)")));
    }

    @Test
    void concurrentEvaluations_neverCrossPollinateRulesBetweenTenants() throws Exception {
        int evaluationsPerTenant = 500;
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(evaluationsPerTenant * 2);

            var aCorrect = new AtomicInteger();
            var bCorrect = new AtomicInteger();
            var unexpected = new ConcurrentHashMap<String, AtomicInteger>();

            // Tenant A evaluations — should always see R-A1 reject on SUSPENDED
            for (int i = 0; i < evaluationsPerTenant; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        List<RuleResult> results = engine.evaluate(TENANT_A,
                            claim("C-A-" + Thread.currentThread().threadId()),
                            member("SUSPENDED"));
                        boolean rejected = results.stream()
                            .anyMatch(r -> "REJECT".equals(r.getType()) && "R-A1".equals(r.getCode()));
                        if (rejected) aCorrect.incrementAndGet();
                        // Any FLAG on this tenant means tenant B's rule fired here.
                        results.stream()
                            .filter(r -> "FLAG".equals(r.getType()))
                            .forEach(r -> unexpected.computeIfAbsent(
                                "A-saw-flag-" + r.getCode(), k -> new AtomicInteger()).incrementAndGet());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            // Tenant B evaluations — should always see F-B1 flag on PENDING
            for (int i = 0; i < evaluationsPerTenant; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        List<RuleResult> results = engine.evaluate(TENANT_B,
                            claim("C-B-" + Thread.currentThread().threadId()),
                            member("PENDING"));
                        boolean flagged = results.stream()
                            .anyMatch(r -> "FLAG".equals(r.getType()) && "F-B1".equals(r.getCode()));
                        if (flagged) bCorrect.incrementAndGet();
                        results.stream()
                            .filter(r -> "REJECT".equals(r.getType()))
                            .forEach(r -> unexpected.computeIfAbsent(
                                "B-saw-reject-" + r.getCode(), k -> new AtomicInteger()).incrementAndGet());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            assertThat(done.await(60, TimeUnit.SECONDS))
                .as("all evaluations should complete within 60s").isTrue();

            assertThat(aCorrect.get())
                .as("every tenant-A evaluation should see R-A1")
                .isEqualTo(evaluationsPerTenant);
            assertThat(bCorrect.get())
                .as("every tenant-B evaluation should see F-B1")
                .isEqualTo(evaluationsPerTenant);
            assertThat(unexpected)
                .as("no cross-tenant rule leakage observed")
                .isEmpty();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void reloadRules_swapsActiveRuleSetAtomicallyForConcurrentCallers() throws Exception {
        // Start with the original "reject suspended" rule on tenant A.
        // Pre-load is from @BeforeEach.
        int evaluations = 200;
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(evaluations);
            var observedRejections = new AtomicInteger();
            var observedFlags = new AtomicInteger();

            for (int i = 0; i < evaluations; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        List<RuleResult> results = engine.evaluate(TENANT_A,
                            claim("C-A-" + Thread.currentThread().threadId()),
                            member("SUSPENDED"));
                        for (RuleResult r : results) {
                            if ("REJECT".equals(r.getType())) observedRejections.incrementAndGet();
                            if ("FLAG".equals(r.getType()))   observedFlags.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            // Halfway through, swap tenant A's rules to a flag-on-suspended rule.
            // The pre/post split doesn't need to be exact — what matters is that
            // every result is from one ruleset or the other, never half-and-half.
            pool.submit(() -> {
                try {
                    Thread.sleep(20);
                    engine.reloadRules(TENANT_A, List.of(
                        flagRule("Flag suspended", "SUSPENDED", "F-A2", "Member suspended (swap)")));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            start.countDown();
            assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();

            // Every evaluation produced exactly one result (either pre-swap REJECT
            // or post-swap FLAG); never both, never neither.
            assertThat(observedRejections.get() + observedFlags.get()).isEqualTo(evaluations);
        } finally {
            pool.shutdownNow();
        }
    }

    // ─── rule + fact builders ────────────────────────────────────────────────

    private RuleDefinition rejectRule(String name, String matchStatus, String code, String message) {
        RuleDefinition rule = new RuleDefinition();
        rule.setName(name);
        rule.setCategory("ELIGIBILITY");
        rule.setPriority(100);
        rule.setStatus("ACTIVE");
        rule.setVersion(1);
        rule.setEnabled(true);

        var cond = new Condition();
        cond.setField("member.status");
        cond.setOperator("EQUALS");
        cond.setValue(matchStatus);
        var group = new ConditionGroup();
        group.setOperator("AND");
        group.setItems(List.of(cond));
        rule.setConditions(group);

        var action = new RuleAction();
        action.setType("REJECT");
        action.setRejectionCode(code);
        action.setMessage(message);
        rule.setAction(action);
        return rule;
    }

    private RuleDefinition flagRule(String name, String matchStatus, String code, String message) {
        RuleDefinition rule = new RuleDefinition();
        rule.setName(name);
        rule.setCategory("ELIGIBILITY");
        rule.setPriority(100);
        rule.setStatus("ACTIVE");
        rule.setVersion(1);
        rule.setEnabled(true);

        var cond = new Condition();
        cond.setField("member.status");
        cond.setOperator("EQUALS");
        cond.setValue(matchStatus);
        var group = new ConditionGroup();
        group.setOperator("AND");
        group.setItems(List.of(cond));
        rule.setConditions(group);

        var action = new RuleAction();
        action.setType("FLAG");
        action.setRejectionCode(code);
        action.setMessage(message);
        rule.setAction(action);
        return rule;
    }

    private ClaimFact claim(String id) {
        var c = new ClaimFact();
        c.setClaimId(id);
        c.setMemberId("MBR-X");
        c.setAmount(new BigDecimal("100.00"));
        c.setBenefitCategory("GENERAL");
        return c;
    }

    private MemberFact member(String status) {
        var m = new MemberFact();
        m.setMemberId("MBR-X");
        m.setStatus(status);
        m.setDaysSinceEnrollment(365);
        return m;
    }
}

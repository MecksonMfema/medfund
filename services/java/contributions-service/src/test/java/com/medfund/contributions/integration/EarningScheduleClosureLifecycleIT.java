package com.medfund.contributions.integration;

import com.medfund.contributions.premium.entity.EarningSchedule;
import com.medfund.contributions.premium.repository.EarningScheduleRepository;
import com.medfund.contributions.premium.service.EarningScheduleClosureService;
import com.medfund.shared.testfixtures.AbstractPolicyStatusConsumerPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 13 §B Phase 6: SQL semantics of the four policy-status-triggered
 * closure lifecycle methods on {@link EarningScheduleClosureService}
 * against a real Postgres. Mocking the fluent R2DBC chain would re-encode
 * the SQL as Mockito stubs — this seam is exactly where the value lies.
 *
 * <p>Every test seeds a curated 12-period LIFE_POLICY strip
 * (2026-01-01 … 2026-12-31) plus a second policy that acts as a
 * scope-check control — the closure predicate must never leak across
 * policies. Idempotency and cross-transition safety are exercised on the
 * same strip.
 *
 * <p>Uses the {@code db/policy-status-consumer-migration/} baseline —
 * same slice pattern as {@link BalanceQueryRepositoryBadDebtsIT}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
    "spring.flyway.locations=classpath:db/policy-status-consumer-migration",
    "spring.flyway.baseline-on-migrate=true",
})
@Import(EarningScheduleClosureLifecycleIT.SecurityStub.class)
class EarningScheduleClosureLifecycleIT extends AbstractPolicyStatusConsumerPostgresIntegrationTest {

    @TestConfiguration
    static class SecurityStub {
        @Bean
        ReactiveJwtDecoder reactiveJwtDecoder() {
            return token -> Mono.just(new Jwt(
                token, Instant.now(), Instant.now().plusSeconds(300),
                Map.of("alg", "none"),
                Map.of("sub", "test", "iss", "test")
            ));
        }
    }

    @Autowired private EarningScheduleClosureService service;
    @Autowired private EarningScheduleRepository earningScheduleRepository;
    @Autowired private DatabaseClient db;

    private static final String TENANT = "00000000-0000-4000-8000-000000000001";
    private UUID policyId;
    private UUID otherPolicyId;

    @BeforeEach
    void resetSchema() {
        db.sql("TRUNCATE earning_schedule, earning_schedule_run CASCADE").then().block();
        policyId = UUID.randomUUID();
        otherPolicyId = UUID.randomUUID();
        // Twelve open periods for the policy under test — 2026-01 through 2026-12.
        for (int month = 1; month <= 12; month++) {
            LocalDate start = LocalDate.of(2026, month, 1);
            LocalDate end = start.plusMonths(1).minusDays(1);
            insertPeriod(policyId, "LIFE_POLICY", "LIFE", start, end,
                    new BigDecimal("100.0000"), null, false, null);
        }
        // Control policy — no lifecycle method should ever touch it.
        insertPeriod(otherPolicyId, "LIFE_POLICY", "LIFE",
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30),
                new BigDecimal("100.0000"), null, false, null);
    }

    // ── closeOutForPolicyClosure ──────────────────────────────────────────

    @Test
    void closeOut_marksFuturePeriods_earned0_isClosureTrue_closureRefSet() {
        UUID ref = UUID.randomUUID();
        Long affected = service.closeOutForPolicyClosure(TENANT, policyId, "LIFE_POLICY",
                LocalDate.of(2026, 5, 1), ref).block();
        assertThat(affected)
                .as("May..Dec = 8 periods on/after 2026-05-01 must close")
                .isEqualTo(8L);

        List<EarningSchedule> rows = earningScheduleRepository
                .findByPolicyIdAndPolicySource(policyId, "LIFE_POLICY")
                .collectList().block();
        assertThat(rows).hasSize(12);
        int closed = 0, open = 0;
        for (EarningSchedule row : rows) {
            if (row.getPeriodStart().getMonthValue() >= 5) {
                assertThat(row.isClosure()).as("closure flag on %s", row.getPeriodStart()).isTrue();
                assertThat(row.getEarnedAtPeriodEnd()).as("earned=0 on %s", row.getPeriodStart())
                        .isEqualByComparingTo("0");
                assertThat(row.getClosureRef()).as("closure_ref on %s", row.getPeriodStart())
                        .isEqualTo(ref);
                closed++;
            } else {
                assertThat(row.isClosure()).isFalse();
                assertThat(row.getEarnedAtPeriodEnd()).isNull();
                assertThat(row.getClosureRef()).isNull();
                open++;
            }
        }
        assertThat(closed).isEqualTo(8);
        assertThat(open).isEqualTo(4);
    }

    @Test
    void closeOut_replayWithSameRef_isIdempotent() {
        UUID ref = UUID.randomUUID();
        Long first = service.closeOutForPolicyClosure(TENANT, policyId, "LIFE_POLICY",
                LocalDate.of(2026, 5, 1), ref).block();
        Long second = service.closeOutForPolicyClosure(TENANT, policyId, "LIFE_POLICY",
                LocalDate.of(2026, 5, 1), ref).block();
        assertThat(first).isEqualTo(8L);
        assertThat(second).as("second run must be a no-op via closure_ref COUNT lookup").isZero();
    }

    @Test
    void closeOut_doesNotTouchOtherPolicies() {
        Long affected = service.closeOutForPolicyClosure(TENANT, policyId, "LIFE_POLICY",
                LocalDate.of(2026, 1, 1), UUID.randomUUID()).block();
        assertThat(affected).isEqualTo(12L);

        EarningSchedule control = earningScheduleRepository
                .findByPolicyIdAndPolicySource(otherPolicyId, "LIFE_POLICY")
                .single().block();
        assertThat(control.isClosure()).isFalse();
        assertThat(control.getEarnedAtPeriodEnd()).isNull();
    }

    @Test
    void closeOut_skipsAlreadyEarnedPeriods() {
        // Simulate a past period already closed by the nightly executor.
        db.sql("""
                UPDATE earning_schedule
                   SET earned_at_period_end = 100
                 WHERE policy_id = :policyId AND period_start = :periodStart
                """)
                .bind("policyId", policyId)
                .bind("periodStart", LocalDate.of(2026, 1, 1))
                .fetch().rowsUpdated().block();

        Long affected = service.closeOutForPolicyClosure(TENANT, policyId, "LIFE_POLICY",
                LocalDate.of(2026, 1, 1), UUID.randomUUID()).block();
        assertThat(affected)
                .as("January was already earned=100 — closure predicate must skip it")
                .isEqualTo(11L);
    }

    // ── freezePolicyEarning + resumePolicyEarning ─────────────────────────

    @Test
    void freeze_marksFuturePeriodsClosure_leavesEarnedNull() {
        UUID ref = UUID.randomUUID();
        Long affected = service.freezePolicyEarning(TENANT, policyId, "LIFE_POLICY",
                LocalDate.of(2026, 7, 1), ref).block();
        assertThat(affected).isEqualTo(6L);

        List<EarningSchedule> rows = earningScheduleRepository
                .findByPolicyIdAndPolicySource(policyId, "LIFE_POLICY")
                .collectList().block();
        for (EarningSchedule row : rows) {
            if (row.getPeriodStart().getMonthValue() >= 7) {
                assertThat(row.isClosure()).isTrue();
                assertThat(row.getClosureRef()).isEqualTo(ref);
                assertThat(row.getEarnedAtPeriodEnd())
                        .as("freeze leaves earned NULL — unwind by resume clears the marker")
                        .isNull();
            }
        }
    }

    @Test
    void resume_reversesFreeze_earnedStillNull_readyForNightlyPass() {
        UUID ref = UUID.randomUUID();
        service.freezePolicyEarning(TENANT, policyId, "LIFE_POLICY",
                LocalDate.of(2026, 7, 1), ref).block();
        Long resumed = service.resumePolicyEarning(TENANT, policyId, "LIFE_POLICY",
                LocalDate.of(2026, 7, 1)).block();
        assertThat(resumed).isEqualTo(6L);

        List<EarningSchedule> rows = earningScheduleRepository
                .findByPolicyIdAndPolicySource(policyId, "LIFE_POLICY")
                .collectList().block();
        for (EarningSchedule row : rows) {
            assertThat(row.isClosure()).isFalse();
            assertThat(row.getClosureRef()).isNull();
            assertThat(row.getEarnedAtPeriodEnd()).isNull();
        }
    }

    @Test
    void resume_doesNotReverseLapseClosure() {
        // Terminate first — earned=0 rows. resume must not touch these.
        service.closeOutForPolicyClosure(TENANT, policyId, "LIFE_POLICY",
                LocalDate.of(2026, 5, 1), UUID.randomUUID()).block();
        Long resumed = service.resumePolicyEarning(TENANT, policyId, "LIFE_POLICY",
                LocalDate.of(2026, 5, 1)).block();
        assertThat(resumed)
                .as("suspend-resume must NOT unwind a lapse — earned=0 rows filtered")
                .isZero();
    }

    // ── reinstatePolicyEarning ────────────────────────────────────────────

    @Test
    void reinstate_reversesClose_reopensPeriodsForNightlyEarn() {
        service.closeOutForPolicyClosure(TENANT, policyId, "LIFE_POLICY",
                LocalDate.of(2026, 5, 1), UUID.randomUUID()).block();
        Long reinstated = service.reinstatePolicyEarning(TENANT, policyId, "LIFE_POLICY",
                LocalDate.of(2026, 5, 1)).block();
        assertThat(reinstated).isEqualTo(8L);

        List<EarningSchedule> rows = earningScheduleRepository
                .findByPolicyIdAndPolicySource(policyId, "LIFE_POLICY")
                .collectList().block();
        for (EarningSchedule row : rows) {
            assertThat(row.isClosure()).isFalse();
            assertThat(row.getClosureRef()).isNull();
            assertThat(row.getEarnedAtPeriodEnd())
                    .as("earned reset to NULL on reinstate — nightly pass earns at original rate")
                    .isNull();
            assertThat(row.getWrittenAmount())
                    .as("original written_amount preserved — pro-rata=1.0 reinstate")
                    .isEqualByComparingTo("100");
        }
    }

    @Test
    void reinstate_doesNotReverseFreeze() {
        service.freezePolicyEarning(TENANT, policyId, "LIFE_POLICY",
                LocalDate.of(2026, 7, 1), UUID.randomUUID()).block();
        Long reinstated = service.reinstatePolicyEarning(TENANT, policyId, "LIFE_POLICY",
                LocalDate.of(2026, 7, 1)).block();
        assertThat(reinstated)
                .as("reinstate is bound to earned=0 closure rows — a frozen row (earned NULL) is skipped")
                .isZero();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private void insertPeriod(UUID policyIdArg, String source, String line,
                              LocalDate start, LocalDate end, BigDecimal written,
                              BigDecimal earned, boolean isClosure, UUID closureRef) {
        var spec = db.sql("""
                INSERT INTO earning_schedule
                    (policy_id, policy_source, insurance_line, period_start, period_end,
                     written_amount, earned_at_period_end, currency_code, is_endorsement,
                     earning_method, is_closure, closure_ref)
                VALUES (:policyId, :source, :line, :start, :end,
                        :written, :earned, 'USD', FALSE,
                        'DAILY_LINEAR', :isClosure, :closureRef)
                """)
                .bind("policyId", policyIdArg)
                .bind("source", source)
                .bind("line", line)
                .bind("start", start)
                .bind("end", end)
                .bind("written", written)
                .bind("isClosure", isClosure);
        spec = earned == null ? spec.bindNull("earned", BigDecimal.class) : spec.bind("earned", earned);
        spec = closureRef == null ? spec.bindNull("closureRef", UUID.class) : spec.bind("closureRef", closureRef);
        spec.fetch().rowsUpdated().block();
    }
}

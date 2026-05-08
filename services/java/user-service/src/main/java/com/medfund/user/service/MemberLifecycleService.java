package com.medfund.user.service;

import com.medfund.rules.fact.MemberLifecycleFact;
import com.medfund.rules.service.RuleEvaluationService;
import com.medfund.rules.service.TenantRuleLoader;
import com.medfund.shared.tenant.TenantContext;
import com.medfund.user.entity.Member;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Runs member-lifecycle rules at the right business moments.
 *
 * <p>Three call points so far:
 * <ul>
 *   <li>{@link #evaluateOnEnrollment(Member)} — fires AGE_GROUP + UNDERWRITING
 *       rules when a new member is created. Outcomes (age band, underwriting
 *       level) live on the returned {@link MemberLifecycleFact} so callers
 *       can audit them or attach them to the row.</li>
 *   <li>{@link #evaluateForRenewal(Member)} — fires MEMBER_LIFECYCLE rules
 *       (auto-renew checks) for an existing active member at scheme
 *       anniversary. Returns whether the member is auto-renew-eligible.</li>
 *   <li>{@link #evaluateForTermination(Member)} — fires the auto-terminate
 *       rule path. Used by the contribution-arrears nightly job to decide
 *       which members to terminate without operator intervention.</li>
 * </ul>
 *
 * <p>Tenants without lifecycle rules see empty outcomes — no behaviour
 * change, exactly the same as the other phase integrations.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberLifecycleService {

    private final TenantRuleLoader          loader;
    private final RuleEvaluationService     evaluator;
    private final MemberLifecycleFactBuilder factBuilder;

    public Mono<MemberLifecycleFact> evaluateOnEnrollment(Member member) {
        return evaluate(member, "ENROLL");
    }

    public Mono<MemberLifecycleFact> evaluateForRenewal(Member member) {
        return evaluate(member, "RENEW");
    }

    /**
     * Run termination rules. The fact's {@code terminationRequested} flag
     * tells the caller whether to flip the member's status; the
     * {@link MemberLifecycleFactBuilder#applyOutcomes(Member, MemberLifecycleFact)}
     * helper does that mutation if the caller wants to apply it.
     */
    public Mono<MemberLifecycleFact> evaluateForTermination(Member member) {
        return evaluate(member, "TERMINATE");
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private Mono<MemberLifecycleFact> evaluate(Member member, String transition) {
        return Mono.deferContextual(ctx -> {
            UUID tenantId = parseTenant(TenantContext.get(ctx));
            if (tenantId == null) {
                log.debug("[lifecycle] no tenant context — skipping rules for member {}", member.getId());
                return factBuilder.build(member, transition).map(MemberLifecycleFactBuilder.Facts::lifecycle);
            }

            return loader.ensureLoaded(tenantId)
                .then(factBuilder.build(member, transition))
                .flatMap(facts -> evaluator
                        .evaluate(tenantId.toString(), facts.lifecycle(), facts.time())
                        .thenReturn(facts.lifecycle()))
                .doOnNext(f -> {
                    if (f.getResults() != null && !f.getResults().isEmpty()) {
                        log.debug("[lifecycle] tenant {} member {} ({}): {} outcomes",
                                tenantId, member.getId(), transition, f.getResults().size());
                    }
                });
        });
    }

    private UUID parseTenant(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return UUID.fromString(raw); } catch (IllegalArgumentException e) { return null; }
    }
}

package com.medfund.user.service;

import com.medfund.rules.fact.MemberLifecycleFact;
import com.medfund.rules.fact.TimeFact;
import com.medfund.user.entity.Member;
import com.medfund.user.repository.MemberRepository;
import com.medfund.user.status.MemberStatusTransitionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Translates a {@link Member} entity into a {@link MemberLifecycleFact}
 * (and a companion {@link TimeFact}) for rules-engine evaluation.
 *
 * <p>One DB lookup against the tenant {@code dependants} table populates the
 * dependant-count column, and an optional second one against
 * the tenant {@code contributions} table computes how many months the member is in
 * arrears. Both are defensive: any failure or empty result yields a
 * default-valued fact so the rules see "not applicable" rather than crash.
 */
@Slf4j
@Component
public class MemberLifecycleFactBuilder {

    private final DatabaseClient db;
    private final MemberRepository memberRepository;
    private final MemberStatusTransitionService statusTransitionService;

    public MemberLifecycleFactBuilder(DatabaseClient db, MemberRepository memberRepository,
                                      MemberStatusTransitionService statusTransitionService) {
        this.db = db;
        this.memberRepository = memberRepository;
        this.statusTransitionService = statusTransitionService;
    }

    public Mono<Facts> build(Member member, String requestedTransition) {
        MemberLifecycleFact base = toFact(member, requestedTransition);
        TimeFact time = TimeFact.of(LocalDate.now());

        Mono<MemberLifecycleFact> enriched = member.getId() == null
                ? Mono.just(base)
                : enrichWithDependantCount(base, member.getId().toString())
                        .flatMap(f -> enrichWithArrears(f, member.getId().toString()));

        return enriched.map(f -> new Facts(f, time));
    }

    private MemberLifecycleFact toFact(Member m, String requestedTransition) {
        MemberLifecycleFact f = new MemberLifecycleFact();
        f.setMemberId(m.getId() != null ? m.getId().toString() : null);
        f.setCurrentStatus(asUpperCase(m.getStatus()));
        f.setRequestedTransition(requestedTransition);
        f.setEnrollmentDate(m.getEnrollmentDate());
        f.setDateOfBirth(m.getDateOfBirth());
        if (m.getDateOfBirth() != null) {
            f.setAge((int) ChronoUnit.YEARS.between(m.getDateOfBirth(), LocalDate.now()));
        }
        f.setGender(asUpperCase(m.getGender()));
        f.setGroupId(m.getGroupId() != null ? m.getGroupId().toString() : null);
        f.setSchemeId(m.getSchemeId() != null ? m.getSchemeId().toString() : null);
        // smoker / bmi / hasPreExistingConditions / region aren't on the
        // current Member entity — defaults to false / 0 / false / null and
        // underwriting rules can read tenant-supplied values from the
        // request-level overrides once that flow exists. Tenants who haven't
        // surfaced these fields yet just see "not flagged" outcomes.
        return f;
    }

    private Mono<MemberLifecycleFact> enrichWithDependantCount(MemberLifecycleFact f, String memberId) {
        return db.sql("SELECT COUNT(*) AS cnt FROM dependants WHERE member_id = :id AND status = 'active'")
                .bind("id", java.util.UUID.fromString(memberId))
                .fetch().one()
                .map(row -> {
                    if (row.get("cnt") instanceof Number n) f.setDependantCount(n.intValue());
                    return f;
                })
                .defaultIfEmpty(f)
                .onErrorResume(err -> {
                    log.debug("[lifecycle-fact] dependant count failed for {}: {}", memberId, err.getMessage());
                    return Mono.just(f);
                });
    }

    private Mono<MemberLifecycleFact> enrichWithArrears(MemberLifecycleFact f, String memberId) {
        // Count distinct YYYY-MM periods of unpaid contributions whose period
        // ended before today. A coarse but useful default for "arrears months".
        return db.sql("""
                SELECT COUNT(DISTINCT DATE_TRUNC('month', period_end)) AS arrears_months
                FROM contributions
                WHERE member_id = :id
                  AND status <> 'paid'
                  AND period_end < CURRENT_DATE
                """)
                .bind("id", java.util.UUID.fromString(memberId))
                .fetch().one()
                .map(row -> {
                    if (row.get("arrears_months") instanceof Number n) {
                        f.setContributionsInArrearsMonths(n.intValue());
                    }
                    return f;
                })
                .defaultIfEmpty(f)
                .onErrorResume(err -> {
                    log.debug("[lifecycle-fact] arrears query failed for {}: {}", memberId, err.getMessage());
                    return Mono.just(f);
                });
    }

    /** Write the post-evaluation outcomes back onto the Member row. */
    public void applyOutcomes(Member member, MemberLifecycleFact fact) {
        // Auto-termination: caller can flip status if the rule fired.
        if (fact.isTerminationRequested() && !"terminated".equalsIgnoreCase(member.getStatus())) {
            member.setStatus("terminated");
            member.setTerminationDate(LocalDate.now());
        }
        // Age group + underwriting level surface in audit only on first cut —
        // adding member columns for them is a follow-up migration.
    }

    /**
     * Phase 13 §A per L3 + grill note 8 — persist-through variant of
     * {@link #applyOutcomes} for the auto-termination path. The old flow
     * wrote {@code member.setStatus("terminated")} directly (the one write
     * site outside the central pathway); this routes through
     * {@link MemberStatusTransitionService} so the flip lands a
     * {@code member_status_history} row with {@code reason_code='auto_termination'}
     * in the same transaction. Mirrors the applyOrSchedule post-step:
     * termination_date stamps after the transitioned save.
     */
    public Mono<Member> applyTermination(Member member, MemberLifecycleFact fact) {
        if (!fact.isTerminationRequested() || "terminated".equalsIgnoreCase(member.getStatus())) {
            return Mono.just(member);
        }
        LocalDate termDate = LocalDate.now();
        return statusTransitionService
                .transition(member.getId(), "terminated", "auto_termination",
                        "Rules-engine auto-termination",
                        null,                      // system-initiated, no actor id
                        "rules-engine@insureflow") // marker email per feedback_audit_actor_email
                .flatMap(terminated -> {
                    terminated.setTerminationDate(termDate);
                    return memberRepository.save(terminated);
                });
    }

    private static String asUpperCase(String s) {
        return s == null ? null : s.toUpperCase();
    }

    /** Pair returned to callers. */
    public record Facts(MemberLifecycleFact lifecycle, TimeFact time) {}
}

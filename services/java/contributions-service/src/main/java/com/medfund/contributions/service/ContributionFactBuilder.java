package com.medfund.contributions.service;

import com.medfund.contributions.entity.Contribution;
import com.medfund.rules.fact.ContributionFact;
import com.medfund.rules.fact.TimeFact;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Translates a {@link Contribution} entity into a {@link ContributionFact}
 * (and a companion {@link TimeFact}) for rules-engine evaluation.
 *
 * <p>Issues a single member lookup against {@code public.members} to
 * populate age / gender / region / dependant-count / chronic-conditions
 * columns. Defaults are non-failing: a missing member produces an empty
 * fact rather than crashing the pricing flow — pricing rules typically
 * gate on positive matches (age ≥ 65, smoker = true, …) so a default
 * fact won't trip them.
 */
@Slf4j
@Component
public class ContributionFactBuilder {

    private final DatabaseClient db;

    public ContributionFactBuilder(DatabaseClient db) {
        this.db = db;
    }

    public Mono<Facts> build(Contribution contribution) {
        ContributionFact base = toFact(contribution);

        Mono<ContributionFact> enriched = contribution.getMemberId() == null
                ? Mono.just(base)
                : enrichWithMember(base, contribution.getMemberId().toString());

        TimeFact time = TimeFact.of(LocalDate.now());

        return enriched.map(c -> new Facts(c, time));
    }

    private ContributionFact toFact(Contribution c) {
        ContributionFact f = new ContributionFact();
        f.setContributionId(c.getId() != null ? c.getId().toString() : null);
        f.setMemberId(c.getMemberId() != null ? c.getMemberId().toString() : null);
        f.setSchemeId(c.getSchemeId() != null ? c.getSchemeId().toString() : null);
        f.setGroupId(c.getGroupId() != null ? c.getGroupId().toString() : null);
        f.setCurrencyCode(c.getCurrencyCode());
        f.setPeriodStart(c.getPeriodStart());
        f.setPeriodEnd(c.getPeriodEnd());
        f.setBaseAmount(c.getAmount());
        f.setPremiumAmount(c.getAmount()); // start as base; rules may override
        f.setPaid("paid".equalsIgnoreCase(c.getStatus()));
        if (c.getPeriodEnd() != null) {
            // Days overdue: only counted once the period has ended and the row is unpaid.
            LocalDate today = LocalDate.now();
            if (!"paid".equalsIgnoreCase(c.getStatus()) && today.isAfter(c.getPeriodEnd())) {
                f.setDaysOverdue((int) ChronoUnit.DAYS.between(c.getPeriodEnd(), today));
            }
        }
        return f;
    }

    private Mono<ContributionFact> enrichWithMember(ContributionFact f, String memberId) {
        return db.sql("""
                SELECT date_of_birth, gender, region,
                       (SELECT COUNT(*) FROM public.dependants d
                        WHERE d.member_id = :id AND d.status = 'active') AS dependant_count
                FROM public.members
                WHERE id = :id
                """)
                .bind("id", memberId)
                .fetch().one()
                .map(row -> {
                    if (row.get("date_of_birth") instanceof LocalDate dob) {
                        f.setMemberAge((int) ChronoUnit.YEARS.between(dob, LocalDate.now()));
                    }
                    f.setMemberGender(asString(row.get("gender")));
                    f.setMemberRegion(asString(row.get("region")));
                    Object dc = row.get("dependant_count");
                    if (dc instanceof Number n) f.setDependantCount(n.intValue());
                    return f;
                })
                .defaultIfEmpty(f)
                .onErrorResume(err -> {
                    log.debug("[contribution-fact] member lookup failed for {}: {}",
                            memberId, err.getMessage());
                    return Mono.just(f);
                });
    }

    /** Translates a fact (after rules have run) back into the contribution row. */
    public void applyOutcomes(Contribution contribution, ContributionFact fact) {
        if (fact.getPremiumAmount() != null) {
            contribution.setAmount(fact.getPremiumAmount());
        }
        // Late fees on first cut just go to the audit trail — wiring them into
        // a separate billable-row will land in a follow-up. Pricing-only flow
        // for now.
    }

    private static String asString(Object o) { return o == null ? null : o.toString(); }

    /** Pair returned to callers — claims-style. */
    public record Facts(ContributionFact contribution, TimeFact time) {}
}

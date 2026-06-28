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
 * <p>Issues a single member lookup against the tenant {@code members} table to
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
        // The medical_history JSONB column (V031) gives the rules engine
        // + AI scorer per-member risk signals — chronic conditions,
        // smoking status, BMI, medication count. Selected here so rules
        // like "if chronicConditionCount >= 2 then amount = base * 1.3"
        // can fire without an extra round-trip. A null medical_history
        // collapses to the safe defaults on ContributionFact (count = 0,
        // smokingStatus = null) — no loading applied.
        return db.sql("""
                SELECT date_of_birth, gender, medical_history,
                       (SELECT COUNT(*) FROM dependants d
                        WHERE d.member_id = :id AND d.status = 'active') AS dependant_count
                FROM members
                WHERE id = :id
                """)
                .bind("id", java.util.UUID.fromString(memberId))
                .fetch().one()
                .map(row -> {
                    if (row.get("date_of_birth") instanceof LocalDate dob) {
                        f.setMemberAge((int) ChronoUnit.YEARS.between(dob, LocalDate.now()));
                    }
                    f.setMemberGender(asString(row.get("gender")));
                    Object dc = row.get("dependant_count");
                    if (dc instanceof Number n) f.setDependantCount(n.intValue());
                    applyMedicalHistory(f, row.get("medical_history"));
                    return f;
                })
                .defaultIfEmpty(f)
                .onErrorResume(err -> {
                    log.debug("[contribution-fact] member lookup failed for {}: {}",
                            memberId, err.getMessage());
                    return Mono.just(f);
                });
    }

    /**
     * Project the medical_history JSONB blob onto BOTH the structured
     * HEALTH-specific fields AND the line-agnostic
     * {@link ContributionFact#getAttributes() attributes} map. Rules
     * written against the typed fields keep working; new rules SHOULD
     * use {@code fact.attribute("key")} so the same DRL fires
     * regardless of which line populated the attributes.
     *
     * <p>Per-line FactBuilder strategies (MOTOR, PROPERTY, LIFE, …)
     * land in this same package once we ship those lines — pick the
     * builder by {@code schemes.insurance_line} and have it populate
     * its own attribute keys. The contract is "rules read attributes;
     * the line's builder writes them".
     *
     * <p>Reads each key defensively — every signal is optional, so a
     * member with a partial profile still yields a usable fact. The
     * blob arrives from r2dbc-postgresql as a
     * {@code io.r2dbc.postgresql.codec.Json} wrapper; falling back to
     * {@code toString()} keeps the parser tolerant of either shape.
     */
    private void applyMedicalHistory(ContributionFact f, Object raw) {
        if (raw == null) return;
        String json = raw.toString();
        if (json.isBlank() || json.equals("{}")) return;
        try {
            com.fasterxml.jackson.databind.JsonNode node =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            if (node.has("chronic_conditions") && node.get("chronic_conditions").isArray()) {
                int count = node.get("chronic_conditions").size();
                f.setChronicConditionCount(count);
                f.getAttributes().put("chronic_condition_count", count);
                if (count > 0) f.setHasChronicConditions(true);
            }
            if (node.hasNonNull("smoking_status")) {
                String s = node.get("smoking_status").asText();
                f.setSmokingStatus(s);
                f.getAttributes().put("smoking_status", s);
            }
            if (node.hasNonNull("bmi")) {
                java.math.BigDecimal bmi = new java.math.BigDecimal(node.get("bmi").asText());
                f.setBmi(bmi);
                f.getAttributes().put("bmi", bmi.doubleValue());
            }
            if (node.hasNonNull("medication_count")) {
                int mc = node.get("medication_count").asInt(0);
                f.setMedicationCount(mc);
                f.getAttributes().put("medication_count", mc);
            }
        } catch (Exception e) {
            log.debug("[contribution-fact] medical_history parse failed: {}", e.getMessage());
        }
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

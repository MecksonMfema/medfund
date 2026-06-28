package com.medfund.contributions.service.factenricher;

import com.medfund.contributions.entity.Contribution;
import com.medfund.rules.fact.ContributionFact;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * HEALTH-line enricher. Reads {@code members.medical_history} JSONB
 * (V031) and projects chronic conditions, smoking status, BMI, and
 * medication count onto the fact. Also populates age / gender /
 * dependant count from the member row.
 *
 * <p>Writes both the legacy typed fields ({@code memberAge},
 * {@code chronicConditionCount}, etc.) AND the line-agnostic
 * {@code attributes} map. Rules using either path keep firing.
 *
 * <p>Logic moved from {@code ContributionFactBuilder.enrichWithMember}
 * + {@code applyMedicalHistory} in Part 1 of the multi-line plan.
 */
@Slf4j
@Component
public class HealthFactEnricher implements LineFactEnricher {

    private final DatabaseClient db;

    public HealthFactEnricher(DatabaseClient db) {
        this.db = db;
    }

    @Override
    public String supportedLine() {
        return "HEALTH";
    }

    @Override
    public Mono<ContributionFact> enrich(ContributionFact base, Contribution contribution) {
        if (contribution.getMemberId() == null) {
            return Mono.just(base);
        }
        String memberId = contribution.getMemberId().toString();
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
                        base.setMemberAge((int) ChronoUnit.YEARS.between(dob, LocalDate.now()));
                    }
                    base.setMemberGender(asString(row.get("gender")));
                    Object dc = row.get("dependant_count");
                    if (dc instanceof Number n) base.setDependantCount(n.intValue());
                    applyMedicalHistory(base, row.get("medical_history"));
                    return base;
                })
                .defaultIfEmpty(base)
                .onErrorResume(err -> {
                    log.debug("[health-fact] member lookup failed for {}: {}",
                            memberId, err.getMessage());
                    return Mono.just(base);
                });
    }

    private static String asString(Object v) {
        return v != null ? v.toString() : null;
    }

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
            log.debug("[health-fact] medical_history parse failed: {}", e.getMessage());
        }
    }
}

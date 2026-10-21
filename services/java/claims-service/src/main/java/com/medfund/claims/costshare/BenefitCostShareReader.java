package com.medfund.claims.costshare;

import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Resolves the {@code benefit_cost_share} rows effective for a set of
 * (benefit, asOf) tuples. One query per calculator run covering every line's
 * benefit_id — avoids N+1 across the claim's lines.
 *
 * <p>The parent benefit's {@code currency_code} is pulled from
 * {@code scheme_benefits} in the same query so downstream code doesn't need a
 * second hop to know the copay currency (G6).
 */
@Component
@RequiredArgsConstructor
public class BenefitCostShareReader {

    private final DatabaseClient databaseClient;

    /**
     * Return every effective row for the given benefit IDs at {@code asOf},
     * one row per benefit. Benefits with no cost-share configured are
     * simply absent from the result.
     */
    public Flux<CostShareConfig.Benefit> findEffective(Collection<UUID> benefitIds, LocalDate asOf) {
        if (benefitIds == null || benefitIds.isEmpty()) return Flux.empty();
        List<UUID> ids = benefitIds.stream().filter(id -> id != null).distinct().toList();
        if (ids.isEmpty()) return Flux.empty();

        // DISTINCT ON keeps only the newest-effective row per benefit — Postgres-specific
        // but the whole platform is Postgres, so no portability concern.
        return databaseClient.sql("""
                SELECT DISTINCT ON (bcs.scheme_benefit_id)
                       bcs.id, bcs.scheme_benefit_id, bcs.copay_type, bcs.copay_amount,
                       bcs.copay_percentage, bcs.copay_max, bcs.coinsurance_rate,
                       bcs.applies_to_deductible, bcs.applies_to_oop_max, bcs.basis,
                       sb.currency_code AS currency_code,
                       bcs.effective_from, bcs.effective_to
                  FROM benefit_cost_share bcs
                  JOIN scheme_benefits    sb ON sb.id = bcs.scheme_benefit_id
                 WHERE bcs.scheme_benefit_id IN (:benefitIds)
                   AND bcs.effective_from <= :asOf
                   AND (bcs.effective_to IS NULL OR bcs.effective_to >= :asOf)
                 ORDER BY bcs.scheme_benefit_id, bcs.effective_from DESC
                """)
                .bind("benefitIds", ids)
                .bind("asOf", asOf)
                .map((row, meta) -> new CostShareConfig.Benefit(
                        row.get("id", UUID.class),
                        row.get("scheme_benefit_id", UUID.class),
                        row.get("copay_type", String.class),
                        row.get("copay_amount", BigDecimal.class),
                        row.get("copay_percentage", BigDecimal.class),
                        row.get("copay_max", BigDecimal.class),
                        row.get("coinsurance_rate", BigDecimal.class),
                        row.get("applies_to_deductible", Boolean.class),
                        row.get("applies_to_oop_max", Boolean.class),
                        row.get("basis", String.class),
                        row.get("currency_code", String.class),
                        row.get("effective_from", LocalDate.class),
                        row.get("effective_to", LocalDate.class)))
                .all();
    }
}

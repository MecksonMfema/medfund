package com.medfund.claims.costshare;

import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Resolves the {@code scheme_cost_share} row effective for a given
 * (scheme, policy_year, asOf) from the tenant schema. Same semantics as
 * contributions-service's {@code SchemeCostShareRepository.findEffective} —
 * unqualified table name (Critical Rule 2), newest {@code effective_from}
 * wins when windows overlap (G15).
 */
@Component
@RequiredArgsConstructor
public class SchemeCostShareReader {

    private final DatabaseClient databaseClient;

    public Mono<CostShareConfig.Scheme> findEffective(UUID schemeId, int policyYear, LocalDate asOf) {
        return databaseClient.sql("""
                SELECT id, scheme_id, policy_year, deductible, out_of_pocket_max,
                       deductible_scope, oop_scope, shortfall_policy, currency_code,
                       effective_from, effective_to
                  FROM scheme_cost_share
                 WHERE scheme_id     = :schemeId
                   AND policy_year   = :policyYear
                   AND effective_from <= :asOf
                   AND (effective_to IS NULL OR effective_to >= :asOf)
                 ORDER BY effective_from DESC
                 LIMIT 1
                """)
                .bind("schemeId", schemeId)
                .bind("policyYear", policyYear)
                .bind("asOf", asOf)
                .map((row, meta) -> new CostShareConfig.Scheme(
                        row.get("id", UUID.class),
                        row.get("scheme_id", UUID.class),
                        row.get("policy_year", Integer.class),
                        row.get("deductible", BigDecimal.class),
                        row.get("out_of_pocket_max", BigDecimal.class),
                        row.get("deductible_scope", String.class),
                        row.get("oop_scope", String.class),
                        row.get("shortfall_policy", String.class),
                        row.get("currency_code", String.class),
                        row.get("effective_from", LocalDate.class),
                        row.get("effective_to", LocalDate.class)))
                .one()
                .onErrorResume(e -> Mono.empty());
    }
}

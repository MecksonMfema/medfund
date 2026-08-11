package com.medfund.claims.costshare;

import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Reads the {@code member_cost_share_accumulator} row for a
 * (member, dependant?, scheme, year) tuple. Under FAMILY scope the
 * caller passes {@code dependantId=null} — the family pot on the
 * principal member. Under INDIVIDUAL scope the caller passes the
 * dependant's id (or null when the claim is against the principal).
 *
 * <p>Missing rows are legal (a member's first claim of the year) —
 * the reader returns an empty Mono and callers substitute
 * {@link CostShareConfig.Accumulator#empty(UUID, UUID, UUID, int, String)}.
 */
@Component
@RequiredArgsConstructor
public class MemberCostShareAccumulatorReader {

    private final DatabaseClient databaseClient;

    public Mono<CostShareConfig.Accumulator> findFor(UUID memberId, UUID dependantId,
                                                     UUID schemeId, int policyYear) {
        String sql = """
                SELECT id, member_id, dependant_id, scheme_id, policy_year,
                       deductible_met, oop_met, copay_count, currency_code, version
                  FROM member_cost_share_accumulator
                 WHERE member_id  = :memberId
                   AND scheme_id  = :schemeId
                   AND policy_year = :policyYear
                   AND %s
                 LIMIT 1
                """.formatted(dependantId != null ? "dependant_id = :dependantId" : "dependant_id IS NULL");

        var spec = databaseClient.sql(sql)
                .bind("memberId", memberId)
                .bind("schemeId", schemeId)
                .bind("policyYear", policyYear);
        if (dependantId != null) {
            spec = spec.bind("dependantId", dependantId);
        }
        return spec
                .map((row, meta) -> new CostShareConfig.Accumulator(
                        row.get("id", UUID.class),
                        row.get("member_id", UUID.class),
                        row.get("dependant_id", UUID.class),
                        row.get("scheme_id", UUID.class),
                        row.get("policy_year", Integer.class),
                        row.get("deductible_met", BigDecimal.class),
                        row.get("oop_met", BigDecimal.class),
                        row.get("copay_count", Integer.class),
                        row.get("currency_code", String.class),
                        row.get("version", Integer.class)))
                .one()
                .onErrorResume(e -> Mono.empty());
    }
}

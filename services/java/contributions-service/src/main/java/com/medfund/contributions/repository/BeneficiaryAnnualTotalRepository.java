package com.medfund.contributions.repository;

import com.medfund.contributions.entity.BeneficiaryAnnualTotal;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.UUID;

public interface BeneficiaryAnnualTotalRepository extends R2dbcRepository<BeneficiaryAnnualTotal, UUID> {

    /** Single row for the cap ledger of a beneficiary in one policy year. */
    @Query("""
            SELECT * FROM beneficiary_annual_totals
             WHERE scheme_id = :schemeId
               AND member_id = :memberId
               AND ((:dependantId IS NULL AND dependant_id IS NULL)
                    OR dependant_id = :dependantId)
               AND policy_year = :policyYear
             LIMIT 1
            """)
    Mono<BeneficiaryAnnualTotal> findOne(UUID schemeId, UUID memberId, UUID dependantId, Integer policyYear);

    /**
     * Seed row for a newly-enrolled beneficiary on a cap-configured scheme.
     * ON CONFLICT DO NOTHING makes replay safe under the partial UNIQUE
     * index defined in V062.
     */
    @Modifying
    @Query("""
            INSERT INTO beneficiary_annual_totals
                (scheme_id, member_id, dependant_id, policy_year, currency_code)
            VALUES
                (:schemeId, :memberId, :dependantId, :policyYear, :currencyCode)
            ON CONFLICT DO NOTHING
            """)
    Mono<Integer> seedRow(UUID schemeId, UUID memberId, UUID dependantId,
                          Integer policyYear, String currencyCode);

    /** Increment the cap ledger by the approved amount for one claim line. */
    @Modifying
    @Query("""
            UPDATE beneficiary_annual_totals
               SET consumed_amount = consumed_amount + :delta,
                   updated_at      = NOW()
             WHERE id = :id
            """)
    Mono<Integer> applyConsumption(UUID id, BigDecimal delta);
}

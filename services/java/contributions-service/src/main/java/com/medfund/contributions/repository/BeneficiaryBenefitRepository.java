package com.medfund.contributions.repository;

import com.medfund.contributions.entity.BeneficiaryBenefit;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.UUID;

public interface BeneficiaryBenefitRepository extends R2dbcRepository<BeneficiaryBenefit, UUID> {

    /** All benefit-year rows for the given beneficiary. When the claim
     *  was captured against a dependant the caller passes both member
     *  (sponsor) and dependant; the query narrows to the dependant's
     *  rows. Otherwise the member's own rows (dependant_id IS NULL). */
    @Query("""
            SELECT * FROM beneficiary_benefits
             WHERE member_id = :memberId
               AND ((:dependantId IS NULL AND dependant_id IS NULL)
                    OR dependant_id = :dependantId)
               AND policy_year = :policyYear
             ORDER BY benefit_id
            """)
    Flux<BeneficiaryBenefit> findFor(UUID memberId, UUID dependantId, Integer policyYear);

    /**
     * Single-row read for the decrement path — the consumer receives one
     * event per approved claim line and needs to find the exact ledger
     * row (memberId, dependantId or member's own, benefitId, policyYear)
     * to increment.
     */
    @Query("""
            SELECT * FROM beneficiary_benefits
             WHERE member_id = :memberId
               AND ((:dependantId IS NULL AND dependant_id IS NULL)
                    OR dependant_id = :dependantId)
               AND benefit_id = :benefitId
               AND policy_year = :policyYear
             LIMIT 1
            """)
    Mono<BeneficiaryBenefit> findOne(UUID memberId, UUID dependantId, UUID benefitId, Integer policyYear);

    /**
     * V061 seeder insert. ON CONFLICT DO NOTHING makes replay safe under
     * the partial unique index defined in V060. Returns row count so the
     * caller can log a "no-op" when the seed was a duplicate replay.
     */
    @Modifying
    @Query("""
            INSERT INTO beneficiary_benefits
                (member_id, dependant_id, benefit_id, scheme_id, policy_year, currency_code)
            VALUES
                (:memberId, :dependantId, :benefitId, :schemeId, :policyYear, :currencyCode)
            ON CONFLICT DO NOTHING
            """)
    Mono<Integer> seedRow(UUID memberId, UUID dependantId, UUID benefitId, UUID schemeId,
                          Integer policyYear, String currencyCode);

    /**
     * V061 decrement update, keyed by the beneficiary-benefit row so we
     * bypass the (member,dependant,benefit,year) COALESCE index lookup on
     * the write path.
     */
    @Modifying
    @Query("""
            UPDATE beneficiary_benefits
               SET consumed_amount = consumed_amount + :delta,
                   consumed_count  = consumed_count + 1,
                   updated_at      = NOW()
             WHERE id = :id
            """)
    Mono<Integer> applyConsumption(UUID id, BigDecimal delta);
}

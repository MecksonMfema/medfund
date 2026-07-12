package com.medfund.claims.repository;

import com.medfund.claims.entity.BeneficiaryBenefit;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface BeneficiaryBenefitRepository extends R2dbcRepository<BeneficiaryBenefit, UUID> {

    /**
     * Find the (beneficiary, benefit, year) row to increment on
     * adjudication. Returns empty when no row exists yet — happens for
     * members enrolled after V060's backfill (until the enrolment hook
     * ships). Callers should treat missing rows as "no counter to
     * update" rather than an error.
     */
    @Query("""
            SELECT * FROM beneficiary_benefits
             WHERE member_id = :memberId
               AND ((:dependantId IS NULL AND dependant_id IS NULL)
                    OR dependant_id = :dependantId)
               AND benefit_id  = :benefitId
               AND policy_year = :policyYear
            """)
    Mono<BeneficiaryBenefit> findOne(UUID memberId, UUID dependantId, UUID benefitId, Integer policyYear);
}

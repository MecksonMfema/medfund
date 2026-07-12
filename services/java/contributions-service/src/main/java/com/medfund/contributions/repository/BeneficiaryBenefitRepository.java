package com.medfund.contributions.repository;

import com.medfund.contributions.entity.BeneficiaryBenefit;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;

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
}

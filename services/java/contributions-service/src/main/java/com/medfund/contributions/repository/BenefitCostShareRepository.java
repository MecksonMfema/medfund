package com.medfund.contributions.repository;

import com.medfund.contributions.entity.BenefitCostShare;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.UUID;

public interface BenefitCostShareRepository extends R2dbcRepository<BenefitCostShare, UUID> {

    /**
     * Resolves the row whose window covers {@code asOf} for the given benefit.
     * Same "newest effective_from wins" tie-break as scheme-level (G15).
     */
    @Query("""
            SELECT * FROM benefit_cost_share
             WHERE scheme_benefit_id = :benefitId
               AND effective_from   <= :asOf
               AND (effective_to IS NULL OR effective_to >= :asOf)
             ORDER BY effective_from DESC
             LIMIT 1
           """)
    Mono<BenefitCostShare> findEffective(UUID benefitId, LocalDate asOf);

    /** All rows for a benefit, newest first. Powers the admin history view. */
    @Query("""
            SELECT * FROM benefit_cost_share
             WHERE scheme_benefit_id = :benefitId
             ORDER BY effective_from DESC
           """)
    Flux<BenefitCostShare> findHistory(UUID benefitId);
}

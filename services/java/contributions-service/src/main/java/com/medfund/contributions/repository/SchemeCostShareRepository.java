package com.medfund.contributions.repository;

import com.medfund.contributions.entity.SchemeCostShare;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.UUID;

public interface SchemeCostShareRepository extends R2dbcRepository<SchemeCostShare, UUID> {

    /**
     * Resolves the row whose window covers {@code asOf} for the given
     * scheme + policy year. Multiple rows in the same year are legal
     * (see G15 temporal edits); ordering by {@code effective_from} DESC
     * picks the most-recent-effective one, matching the way tenant admins
     * expect "yesterday I moved the deductible from $500 to $750" to work.
     */
    @Query("""
            SELECT * FROM scheme_cost_share
             WHERE scheme_id     = :schemeId
               AND policy_year   = :policyYear
               AND effective_from <= :asOf
               AND (effective_to IS NULL OR effective_to >= :asOf)
             ORDER BY effective_from DESC
             LIMIT 1
           """)
    Mono<SchemeCostShare> findEffective(UUID schemeId, int policyYear, LocalDate asOf);

    /** All rows for a scheme+year, newest first. Powers the admin history view. */
    @Query("""
            SELECT * FROM scheme_cost_share
             WHERE scheme_id   = :schemeId
               AND policy_year = :policyYear
             ORDER BY effective_from DESC
           """)
    Flux<SchemeCostShare> findHistory(UUID schemeId, int policyYear);
}

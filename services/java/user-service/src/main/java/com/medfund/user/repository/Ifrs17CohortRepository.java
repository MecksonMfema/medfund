package com.medfund.user.repository;

import com.medfund.user.entity.Ifrs17Cohort;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface Ifrs17CohortRepository extends R2dbcRepository<Ifrs17Cohort, UUID> {

    @Query("SELECT EXISTS(SELECT 1 FROM ifrs17_cohort WHERE portfolio_id = :portfolioId AND cohort_year = :cohortYear AND cohort_type = :cohortType)")
    Mono<Boolean> existsByCompositeKey(UUID portfolioId, Integer cohortYear, String cohortType);

    @Query("SELECT EXISTS(SELECT 1 FROM ifrs17_cohort WHERE portfolio_id = :portfolioId AND cohort_year = :cohortYear AND cohort_type = :cohortType AND id <> :excludeId)")
    Mono<Boolean> existsByCompositeKeyAndIdNot(UUID portfolioId, Integer cohortYear, String cohortType, UUID excludeId);

    @Query("SELECT * FROM ifrs17_cohort WHERE is_active = TRUE ORDER BY cohort_year DESC, name")
    Flux<Ifrs17Cohort> findAllActive();

    @Query("SELECT * FROM ifrs17_cohort ORDER BY is_active DESC, cohort_year DESC, name")
    Flux<Ifrs17Cohort> findAllOrdered();

    @Query("SELECT * FROM ifrs17_cohort WHERE portfolio_id = :portfolioId ORDER BY cohort_year DESC, name")
    Flux<Ifrs17Cohort> findByPortfolioId(UUID portfolioId);

    @Query("""
        SELECT (SELECT COUNT(*) FROM life_policies WHERE cohort_id = :cohortId)
             + (SELECT COUNT(*) FROM funeral_policies WHERE cohort_id = :cohortId)
             + (SELECT COUNT(*) FROM disability_policies WHERE cohort_id = :cohortId)
             + (SELECT COUNT(*) FROM travel_policies WHERE cohort_id = :cohortId)
             + (SELECT COUNT(*) FROM vehicles WHERE cohort_id = :cohortId)
             + (SELECT COUNT(*) FROM properties WHERE cohort_id = :cohortId)
        """)
    Mono<Long> countReferencingPolicies(UUID cohortId);
}

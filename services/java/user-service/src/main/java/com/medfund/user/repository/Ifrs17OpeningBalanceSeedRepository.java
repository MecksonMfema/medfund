package com.medfund.user.repository;

import com.medfund.user.entity.Ifrs17OpeningBalanceSeed;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.UUID;

public interface Ifrs17OpeningBalanceSeedRepository extends R2dbcRepository<Ifrs17OpeningBalanceSeed, UUID> {

    @Query("""
            SELECT * FROM ifrs17_opening_balance_seed
            ORDER BY portfolio_id, cohort_id, currency, balance_type, effective_from DESC
            """)
    Flux<Ifrs17OpeningBalanceSeed> findAllOrdered();

    /**
     * Lookup consulted by §17 shaping: the latest seed row on-or-before
     * {@code asOf} for the tuple, if any.
     */
    @Query("""
            SELECT * FROM ifrs17_opening_balance_seed
            WHERE portfolio_id = :portfolioId
              AND cohort_id = :cohortId
              AND currency = :currency
              AND balance_type = :balanceType
              AND effective_from <= :asOf
            ORDER BY effective_from DESC
            LIMIT 1
            """)
    Mono<Ifrs17OpeningBalanceSeed> findLatestFor(UUID portfolioId, UUID cohortId,
                                                 String currency, String balanceType,
                                                 LocalDate asOf);
}

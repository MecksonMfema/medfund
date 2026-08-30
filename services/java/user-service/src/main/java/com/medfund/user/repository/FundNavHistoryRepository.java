package com.medfund.user.repository;

import com.medfund.user.entity.FundNavHistory;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.UUID;

public interface FundNavHistoryRepository extends R2dbcRepository<FundNavHistory, UUID> {

    @Query("""
            SELECT * FROM fund_nav_history
            WHERE fund_id = :fundId
            ORDER BY valuation_date DESC
            """)
    Flux<FundNavHistory> findByFundIdOrderByValuationDateDesc(UUID fundId);

    /**
     * Latest NAV on-or-before {@code asOf}. §16 VFA reads this at the reporting
     * period boundary.
     */
    @Query("""
            SELECT * FROM fund_nav_history
            WHERE fund_id = :fundId AND valuation_date <= :asOf
            ORDER BY valuation_date DESC
            LIMIT 1
            """)
    Mono<FundNavHistory> findLatestFor(UUID fundId, LocalDate asOf);
}

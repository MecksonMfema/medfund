package com.medfund.user.repository;

import com.medfund.user.entity.Ifrs17Portfolio;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface Ifrs17PortfolioRepository extends R2dbcRepository<Ifrs17Portfolio, UUID> {

    @Query("SELECT EXISTS(SELECT 1 FROM ifrs17_portfolio WHERE LOWER(name) = LOWER(:name))")
    Mono<Boolean> existsByNameIgnoreCase(String name);

    @Query("SELECT EXISTS(SELECT 1 FROM ifrs17_portfolio WHERE LOWER(name) = LOWER(:name) AND id <> :excludeId)")
    Mono<Boolean> existsByNameIgnoreCaseAndIdNot(String name, UUID excludeId);

    @Query("SELECT * FROM ifrs17_portfolio WHERE is_active = TRUE ORDER BY name")
    Flux<Ifrs17Portfolio> findAllActive();

    @Query("SELECT * FROM ifrs17_portfolio ORDER BY is_active DESC, name")
    Flux<Ifrs17Portfolio> findAllOrdered();

    @Query("""
        SELECT * FROM ifrs17_portfolio
        WHERE LOWER(name) LIKE LOWER(CONCAT('%', :q, '%'))
        ORDER BY name
        LIMIT :limit
        """)
    Flux<Ifrs17Portfolio> search(String q, int limit);

    @Query("""
        SELECT (SELECT COUNT(*) FROM life_policies WHERE portfolio_id = :portfolioId)
             + (SELECT COUNT(*) FROM funeral_policies WHERE portfolio_id = :portfolioId)
             + (SELECT COUNT(*) FROM disability_policies WHERE portfolio_id = :portfolioId)
             + (SELECT COUNT(*) FROM travel_policies WHERE portfolio_id = :portfolioId)
             + (SELECT COUNT(*) FROM vehicles WHERE portfolio_id = :portfolioId)
             + (SELECT COUNT(*) FROM properties WHERE portfolio_id = :portfolioId)
        """)
    Mono<Long> countReferencingPolicies(UUID portfolioId);
}

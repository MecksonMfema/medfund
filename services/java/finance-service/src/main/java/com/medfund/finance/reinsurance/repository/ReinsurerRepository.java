package com.medfund.finance.reinsurance.repository;

import com.medfund.finance.reinsurance.entity.Reinsurer;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface ReinsurerRepository extends R2dbcRepository<Reinsurer, UUID> {

    @Query("SELECT * FROM reinsurer ORDER BY LOWER(name) OFFSET :offset LIMIT :limit")
    Flux<Reinsurer> findPage(int offset, int limit);

    @Query("SELECT * FROM reinsurer WHERE is_active = :active ORDER BY LOWER(name) OFFSET :offset LIMIT :limit")
    Flux<Reinsurer> findPageByActive(boolean active, int offset, int limit);

    @Query("SELECT COUNT(*) FROM reinsurer")
    Mono<Long> countAll();

    @Query("SELECT COUNT(*) FROM reinsurer WHERE is_active = :active")
    Mono<Long> countByActive(boolean active);
}

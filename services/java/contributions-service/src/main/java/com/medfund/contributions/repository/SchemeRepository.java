package com.medfund.contributions.repository;

import com.medfund.contributions.entity.Scheme;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface SchemeRepository extends R2dbcRepository<Scheme, UUID> {

    @Query("SELECT * FROM schemes WHERE status = :status ORDER BY name")
    Flux<Scheme> findByStatus(String status);

    @Query("SELECT * FROM schemes ORDER BY name")
    Flux<Scheme> findAllOrderByName();

    @Query("SELECT * FROM schemes WHERE name = :name")
    Mono<Scheme> findByName(String name);

    @Query("SELECT EXISTS(SELECT 1 FROM schemes WHERE name = :name)")
    Mono<Boolean> existsByName(String name);

    @Query("""
        SELECT * FROM schemes
        WHERE LOWER(name) LIKE LOWER(CONCAT('%', :q, '%'))
           OR LOWER(scheme_type) LIKE LOWER(CONCAT('%', :q, '%'))
        ORDER BY name
        LIMIT :limit
        """)
    Flux<Scheme> search(String q, int limit);
}

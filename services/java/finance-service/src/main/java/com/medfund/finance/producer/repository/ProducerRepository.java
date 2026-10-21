package com.medfund.finance.producer.repository;

import com.medfund.finance.producer.entity.Producer;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface ProducerRepository extends R2dbcRepository<Producer, UUID> {

    @Query("SELECT * FROM producer WHERE producer_code = :code")
    Mono<Producer> findByProducerCode(String code);

    @Query("SELECT * FROM producer WHERE is_active = TRUE ORDER BY LOWER(name)")
    Flux<Producer> findActiveOrderByName();

    @Query("SELECT * FROM producer ORDER BY LOWER(name) OFFSET :offset LIMIT :limit")
    Flux<Producer> findPage(int offset, int limit);

    @Query("SELECT * FROM producer WHERE is_active = :active ORDER BY LOWER(name) OFFSET :offset LIMIT :limit")
    Flux<Producer> findPageByActive(boolean active, int offset, int limit);

    @Query("SELECT COUNT(*) FROM producer")
    Mono<Long> countAll();

    @Query("SELECT COUNT(*) FROM producer WHERE is_active = :active")
    Mono<Long> countByActive(boolean active);

    @Query("SELECT * FROM producer WHERE parent_producer_id = :parentId ORDER BY LOWER(name)")
    Flux<Producer> findChildren(UUID parentId);

    /**
     * Walk upward from the given producer to the root. Used to prevent cycles
     * on reparent and to power the admin hierarchy view.
     */
    @Query("""
            WITH RECURSIVE ancestry AS (
                SELECT * FROM producer WHERE id = :id
                UNION ALL
                SELECT p.* FROM producer p JOIN ancestry a ON p.id = a.parent_producer_id
            )
            SELECT * FROM ancestry
            """)
    Flux<Producer> findAncestryOf(UUID id);

    @Query("""
            SELECT * FROM producer
             WHERE is_active = TRUE
               AND (LOWER(name) LIKE LOWER(CONCAT('%', :q, '%'))
                    OR LOWER(producer_code) LIKE LOWER(CONCAT('%', :q, '%')))
             ORDER BY LOWER(name)
             LIMIT :limit
            """)
    Flux<Producer> search(String q, int limit);
}

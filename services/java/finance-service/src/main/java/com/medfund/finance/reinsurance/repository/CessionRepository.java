package com.medfund.finance.reinsurance.repository;

import com.medfund.finance.reinsurance.entity.Cession;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface CessionRepository extends R2dbcRepository<Cession, UUID> {

    /**
     * Idempotency lookup used by the loss/premium cession consumers before
     * writing a new row. The UNIQUE index {@code ux_cession_source_event}
     * would catch a duplicate at write-time anyway, but reading first lets
     * the service log a friendly "already ceded" and skip cleanly instead
     * of relying on unique-violation exception decoding.
     */
    @Query("""
        SELECT * FROM cession
         WHERE treaty_id = :treatyId
           AND source_event_id = :sourceEventId
           AND cession_type = :cessionType
        """)
    Mono<Cession> findByTreatyIdAndSourceEventIdAndCessionType(
            UUID treatyId, UUID sourceEventId, String cessionType);

    /**
     * All cessions previously written against a given source event id (a
     * claim id for LOSS, a contribution id for PREMIUM). Used by the
     * Phase 8 regression-detection path and by the recovery-cascade on
     * facultative void.
     */
    @Query("SELECT * FROM cession WHERE source_event_id = :sourceEventId")
    Flux<Cession> findBySourceEventId(UUID sourceEventId);

    @Query("SELECT * FROM cession WHERE treaty_id = :treatyId ORDER BY occurred_at DESC")
    Flux<Cession> findByTreatyId(UUID treatyId);
}

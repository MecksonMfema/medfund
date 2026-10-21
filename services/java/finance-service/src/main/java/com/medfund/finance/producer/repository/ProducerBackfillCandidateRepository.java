package com.medfund.finance.producer.repository;

import com.medfund.finance.producer.entity.ProducerBackfillCandidate;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface ProducerBackfillCandidateRepository extends R2dbcRepository<ProducerBackfillCandidate, UUID> {

    @Query("""
        SELECT * FROM producer_backfill_candidate
         WHERE status = :status
         ORDER BY confidence_score DESC, created_at ASC
         OFFSET :offset LIMIT :limit
        """)
    Flux<ProducerBackfillCandidate> findByStatusOrderByConfidenceScoreDesc(
            String status, int offset, int limit);

    @Query("SELECT COUNT(*) FROM producer_backfill_candidate WHERE status = :status")
    Mono<Long> countByStatus(String status);

    @Query("""
        SELECT * FROM producer_backfill_candidate
         WHERE treaty_id = :treatyId
           AND status = 'PENDING'
           AND id <> :excludeId
        """)
    Flux<ProducerBackfillCandidate> findSiblingsPendingFor(UUID treatyId, UUID excludeId);

    @Query("""
        SELECT * FROM producer_backfill_candidate
         WHERE treaty_id = :treatyId AND candidate_producer_id = :candidateProducerId
        """)
    Mono<ProducerBackfillCandidate> findByTreatyAndCandidate(UUID treatyId, UUID candidateProducerId);
}

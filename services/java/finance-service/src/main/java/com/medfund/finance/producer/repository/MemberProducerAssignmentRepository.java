package com.medfund.finance.producer.repository;

import com.medfund.finance.producer.entity.MemberProducerAssignment;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.UUID;

public interface MemberProducerAssignmentRepository
        extends R2dbcRepository<MemberProducerAssignment, UUID> {

    /**
     * The single active assignment for a member on a given date (or empty
     * for a producer-less gap).
     */
    @Query("""
            SELECT * FROM member_producer_assignment
             WHERE member_id = :memberId
               AND effective_from <= :asOf
               AND (effective_to IS NULL OR effective_to >= :asOf)
             ORDER BY effective_from DESC
             LIMIT 1
            """)
    Mono<MemberProducerAssignment> findActiveFor(UUID memberId, LocalDate asOf);

    @Query("""
            SELECT * FROM member_producer_assignment
             WHERE member_id = :memberId AND effective_to IS NULL
             LIMIT 1
            """)
    Mono<MemberProducerAssignment> findOpenByMember(UUID memberId);

    @Query("SELECT * FROM member_producer_assignment WHERE member_id = :memberId ORDER BY effective_from DESC")
    Flux<MemberProducerAssignment> findHistoryFor(UUID memberId);

    @Query("SELECT * FROM member_producer_assignment WHERE producer_id = :producerId AND effective_to IS NULL")
    Flux<MemberProducerAssignment> findOpenByProducer(UUID producerId);

    @Query("""
            SELECT * FROM member_producer_assignment
             WHERE producer_id = :producerId AND effective_to IS NULL
             ORDER BY created_at DESC
             OFFSET :offset LIMIT :limit
            """)
    Flux<MemberProducerAssignment> findOpenByProducerPage(UUID producerId, int offset, int limit);

    @Query("SELECT COUNT(*) FROM member_producer_assignment WHERE producer_id = :producerId AND effective_to IS NULL")
    Mono<Long> countOpenByProducer(UUID producerId);
}

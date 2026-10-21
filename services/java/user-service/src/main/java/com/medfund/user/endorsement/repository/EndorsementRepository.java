package com.medfund.user.endorsement.repository;

import com.medfund.user.endorsement.entity.Endorsement;
import org.springframework.data.domain.Pageable;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.Collection;
import java.util.UUID;

public interface EndorsementRepository extends R2dbcRepository<Endorsement, UUID> {

    Flux<Endorsement> findByPolicyIdAndPolicySourceOrderByCreatedAtDesc(UUID policyId, String policySource);

    Flux<Endorsement> findByStatus(String status);

    @Query("""
            SELECT * FROM endorsement
             WHERE status IN (:statuses)
             ORDER BY created_at
             LIMIT :#{#pageable.pageSize}
             OFFSET :#{#pageable.offset}
            """)
    Flux<Endorsement> findByStatusInOrderByCreatedAtAsc(Collection<String> statuses, Pageable pageable);

    @Query("""
            SELECT COUNT(*) FROM endorsement
             WHERE status IN (:statuses)
            """)
    Mono<Long> countByStatusIn(Collection<String> statuses);

    @Query("SELECT COUNT(*) FROM endorsement WHERE reference LIKE :prefix || '%'")
    Mono<Long> countByReferenceStartingWith(String prefix);

    @Query("""
            SELECT * FROM endorsement
             WHERE status = 'COMMITTED' AND effective_from >= :periodStart AND effective_from < :periodEnd
             ORDER BY effective_from
            """)
    Flux<Endorsement> findCommittedInPeriod(LocalDate periodStart, LocalDate periodEnd);
}

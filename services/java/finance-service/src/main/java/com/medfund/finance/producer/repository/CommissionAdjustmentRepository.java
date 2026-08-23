package com.medfund.finance.producer.repository;

import com.medfund.finance.producer.entity.CommissionAdjustment;
import org.springframework.data.domain.Pageable;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.UUID;

public interface CommissionAdjustmentRepository extends R2dbcRepository<CommissionAdjustment, UUID> {

    Flux<CommissionAdjustment> findByStatus(String status);

    Flux<CommissionAdjustment> findByTargetCommissionTransactionId(UUID targetCommissionTransactionId);

    /**
     * Paginated queue for the drafter/approver surfaces. Sorted oldest-first
     * so long-waiting rows surface at the top.
     */
    @Query("""
            SELECT * FROM commission_adjustment
             WHERE status IN (:statuses)
             ORDER BY created_at
             LIMIT :#{#pageable.pageSize}
             OFFSET :#{#pageable.offset}
            """)
    Flux<CommissionAdjustment> findByStatusInOrderByCreatedAtAsc(Collection<String> statuses, Pageable pageable);

    @Query("""
            SELECT COUNT(*) FROM commission_adjustment
             WHERE status IN (:statuses)
            """)
    Mono<Long> countByStatusIn(Collection<String> statuses);

    @Query("SELECT COUNT(*) FROM commission_adjustment WHERE reference LIKE :prefix || '%'")
    Mono<Long> countByReferenceStartingWith(String prefix);
}

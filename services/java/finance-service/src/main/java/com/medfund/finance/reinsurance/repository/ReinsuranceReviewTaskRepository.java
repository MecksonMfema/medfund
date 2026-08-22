package com.medfund.finance.reinsurance.repository;

import com.medfund.finance.reinsurance.entity.ReinsuranceReviewTask;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface ReinsuranceReviewTaskRepository extends R2dbcRepository<ReinsuranceReviewTask, UUID> {

    /**
     * Queue view — OPEN + IN_PROGRESS, oldest-first (the partial index
     * {@code ix_reinsurance_review_open} covers this predicate). When
     * {@code status} is null both buckets are returned.
     */
    @Query("""
        SELECT * FROM reinsurance_review_task
         WHERE status IN ('OPEN', 'IN_PROGRESS')
         ORDER BY created_at ASC
         OFFSET :offset LIMIT :limit
        """)
    Flux<ReinsuranceReviewTask> findOpenQueue(int offset, int limit);

    @Query("""
        SELECT * FROM reinsurance_review_task
         WHERE status = :status
         ORDER BY created_at ASC
         OFFSET :offset LIMIT :limit
        """)
    Flux<ReinsuranceReviewTask> findByStatus(String status, int offset, int limit);

    @Query("SELECT COUNT(*) FROM reinsurance_review_task WHERE status IN ('OPEN', 'IN_PROGRESS')")
    Mono<Long> countOpen();

    @Query("SELECT COUNT(*) FROM reinsurance_review_task WHERE status = :status")
    Mono<Long> countByStatus(String status);

    /**
     * Duplicate-guard for regression-task creation — the loss cession
     * consumer only opens a new task per (cession, task_type) pair when
     * none already exists. Without this guard, every re-adjudicated
     * event would create a fresh task each time it landed.
     */
    @Query("""
        SELECT * FROM reinsurance_review_task
         WHERE cession_id = :cessionId
           AND task_type = :taskType
           AND status IN ('OPEN', 'IN_PROGRESS')
         LIMIT 1
        """)
    Mono<ReinsuranceReviewTask> findOpenByCessionAndType(UUID cessionId, String taskType);

    @Query("SELECT * FROM reinsurance_review_task WHERE claim_id = :claimId")
    Flux<ReinsuranceReviewTask> findByClaimId(UUID claimId);
}

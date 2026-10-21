package com.medfund.user.repository;

import com.medfund.user.entity.CohortStatusHistory;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface CohortStatusHistoryRepository extends R2dbcRepository<CohortStatusHistory, UUID> {

    @Query("SELECT * FROM cohort_status_history WHERE cohort_id = :cohortId ORDER BY effective_at DESC, created_at DESC")
    Flux<CohortStatusHistory> findByCohortIdOrderByEffectiveAtDesc(UUID cohortId);

    /**
     * Idempotency lookup for the §15 auto-transition callback: if a row
     * already exists for (cohortId, sourceRunId) the caller returns it
     * as a no-op without inserting a duplicate movement or firing a
     * second material event. Matches the retry semantics of the
     * outbox publisher that drives the ai-service HTTP callback.
     */
    @Query("SELECT * FROM cohort_status_history WHERE cohort_id = :cohortId AND source_run_id = :sourceRunId LIMIT 1")
    Mono<CohortStatusHistory> findByCohortIdAndSourceRunId(UUID cohortId, UUID sourceRunId);
}

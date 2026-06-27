package com.medfund.shared.scheduler;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

public interface ScheduledJobRunRepository extends R2dbcRepository<ScheduledJobRun, UUID> {

    @Query("SELECT * FROM public.scheduled_job_runs WHERE config_id = :configId " +
           "ORDER BY started_at DESC LIMIT :limit")
    Flux<ScheduledJobRun> findRecent(UUID configId, int limit);

    @Query("SELECT * FROM public.scheduled_job_runs WHERE config_id = :configId " +
           "AND tenant_id = :tenantId ORDER BY started_at DESC LIMIT :limit")
    Flux<ScheduledJobRun> findRecentForTenant(UUID configId, UUID tenantId, int limit);

    @Query("SELECT * FROM public.scheduled_job_runs WHERE status = :status " +
           "ORDER BY started_at DESC LIMIT :limit")
    Flux<ScheduledJobRun> findByStatus(String status, int limit);

    @Query("SELECT COUNT(*) FROM public.scheduled_job_runs " +
           "WHERE config_id = :configId AND status = 'FAILED' " +
           "AND started_at > NOW() - INTERVAL '24 hours'")
    Mono<Long> countRecentFailures(UUID configId);

    /**
     * Recent runs the given actor kicked off, plus anything still
     * RUNNING regardless of when it started. Used by the header bell
     * dropdown so the operator always sees in-flight work even if they
     * triggered it before the {@code since} window. The dropdown polls
     * this every 30s; cap is {@code limit} rows.
     */
    @Query("SELECT * FROM public.scheduled_job_runs " +
           " WHERE triggered_by = :actorId " +
           "   AND (status = 'RUNNING' OR ended_at >= :since) " +
           " ORDER BY started_at DESC LIMIT :limit")
    Flux<ScheduledJobRun> findRecentForActor(UUID actorId, Instant since, int limit);
}

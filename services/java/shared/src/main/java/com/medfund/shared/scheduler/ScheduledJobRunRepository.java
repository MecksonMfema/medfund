package com.medfund.shared.scheduler;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface ScheduledJobRunRepository extends R2dbcRepository<ScheduledJobRun, UUID> {

    @Query("SELECT * FROM scheduled_job_runs WHERE config_id = :configId " +
           "ORDER BY started_at DESC LIMIT :limit")
    Flux<ScheduledJobRun> findRecent(UUID configId, int limit);

    @Query("SELECT * FROM scheduled_job_runs WHERE status = :status " +
           "ORDER BY started_at DESC LIMIT :limit")
    Flux<ScheduledJobRun> findByStatus(String status, int limit);

    @Query("SELECT COUNT(*) FROM scheduled_job_runs " +
           "WHERE config_id = :configId AND status = 'FAILED' " +
           "AND started_at > NOW() - INTERVAL '24 hours'")
    Mono<Long> countRecentFailures(UUID configId);
}

package com.medfund.shared.scheduler;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

public interface ScheduledJobRepository extends R2dbcRepository<ScheduledJobConfig, UUID> {

    @Query("SELECT * FROM public.scheduled_job_configs WHERE is_enabled = true AND next_execution_at <= :now")
    Flux<ScheduledJobConfig> findDueJobs(Instant now);

    @Query("SELECT * FROM public.scheduled_job_configs WHERE job_type = :jobType LIMIT 1")
    Mono<ScheduledJobConfig> findByJobType(String jobType);

    @Query("SELECT * FROM public.scheduled_job_configs ORDER BY tenant_id NULLS FIRST, job_type")
    Flux<ScheduledJobConfig> findAllOrderByJobType();

    @Query("SELECT * FROM public.scheduled_job_configs WHERE tenant_id = :tenantId ORDER BY job_type")
    Flux<ScheduledJobConfig> findAllByTenant(UUID tenantId);

    @Query("SELECT * FROM public.scheduled_job_configs WHERE is_enabled = true ORDER BY job_type")
    Flux<ScheduledJobConfig> findAllEnabled();
}

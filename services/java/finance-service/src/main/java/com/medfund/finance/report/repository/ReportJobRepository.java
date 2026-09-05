package com.medfund.finance.report.repository;

import com.medfund.finance.report.entity.ReportJob;
import io.r2dbc.postgresql.codec.Json;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface ReportJobRepository extends ReactiveCrudRepository<ReportJob, UUID> {

    Flux<ReportJob> findByTenantIdAndParamsHash(UUID tenantId, String paramsHash);

    Mono<ReportJob> findFirstByTenantIdAndParamsHashAndStatusInOrderByRequestedAtDesc(
            UUID tenantId, String paramsHash, List<String> statuses);

    /**
     * Most recent completed IBNR (or LOSS) triangle for the tenant, at or
     * after {@code completedFloor}. Used by
     * {@link com.medfund.finance.ifrs17.service.IbnrSubJobOrchestrator} to
     * decide whether to publish a fresh IBNR sub-job before firing the IFRS 17
     * LIC chunks that consume it.
     */
    Mono<ReportJob> findFirstByTenantIdAndReportKeyAndStatusAndCompletedAtGreaterThanEqualOrderByCompletedAtDesc(
            UUID tenantId, String reportKey, String status, OffsetDateTime completedFloor);

    /**
     * Phase 17 §A.2 — pre-flight dedup lookup for a scheduled fire. Returns
     * the existing SCHEDULED row for this (tenant, key, schedule, period) if
     * another instance already fired the same tick. The partial UNIQUE index
     * {@code ux_report_job_schedule_dedup} is the hard guard; this method is
     * a cheap advisory check that avoids the exception path on the common
     * happy case (single-instance) without changing correctness on multi-
     * instance (the INSERT will still race and lose cleanly).
     */
    Mono<ReportJob> findFirstByTenantIdAndReportKeyAndScheduleIdAndPeriodStart(
            UUID tenantId, String reportKey, UUID scheduleId, LocalDate periodStart);

    /** Phase 17 §A.2 — terminal-mark a scheduled fire as completed. */
    @Modifying
    @Query("UPDATE report_job SET status='completed', result_json=:result, completed_at=:completedAt "
            + "WHERE job_id=:jobId")
    Mono<Integer> markCompleted(UUID jobId, Json result, OffsetDateTime completedAt);

    /** Phase 17 §A.2 — terminal-mark a scheduled fire as failed. */
    @Modifying
    @Query("UPDATE report_job SET status='failed', error_message=:message, completed_at=:completedAt "
            + "WHERE job_id=:jobId")
    Mono<Integer> markFailed(UUID jobId, String message, OffsetDateTime completedAt);

    /**
     * Phase 17 §C.1 — run-history feed for the tenant-admin schedule detail
     * accordion. Returns the newest {@code limit} scheduled fires for a given
     * schedule, ordered by requested_at DESC. Ad-hoc jobs (schedule_id IS NULL)
     * are excluded by the filter.
     */
    @Query("SELECT * FROM report_job "
            + "WHERE tenant_id = :tenantId AND schedule_id = :scheduleId "
            + "ORDER BY requested_at DESC LIMIT :limit")
    Flux<ReportJob> findByScheduleIdOrderedDesc(UUID tenantId, UUID scheduleId, int limit);
}

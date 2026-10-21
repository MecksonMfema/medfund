package com.medfund.finance.report.schedule.dto;

import com.medfund.finance.report.entity.ReportJob;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Phase 17 §C.1 — one row in the schedule run-history feed consumed by the
 * tenant-admin Angular page. Slim projection of {@link ReportJob} that
 * excludes the raw {@code paramsJson} and {@code resultJson} blobs (the UI
 * only needs the metadata; the XLSX is fetched separately via the in-app
 * download endpoint).
 */
public record ScheduledRunResponse(
        UUID jobId,
        UUID scheduleId,
        String reportKey,
        String status,
        String errorMessage,
        LocalDate periodStart,
        LocalDate periodEnd,
        OffsetDateTime requestedAt,
        OffsetDateTime completedAt,
        boolean hasXlsx) {

    public static ScheduledRunResponse from(ReportJob job, boolean hasXlsx) {
        return new ScheduledRunResponse(
                job.getJobId(),
                job.getScheduleId(),
                job.getReportKey(),
                job.getStatus(),
                job.getErrorMessage(),
                job.getPeriodStart(),
                job.getPeriodEnd(),
                job.getRequestedAt(),
                job.getCompletedAt(),
                hasXlsx);
    }
}

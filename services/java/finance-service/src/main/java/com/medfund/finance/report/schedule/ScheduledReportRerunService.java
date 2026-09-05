package com.medfund.finance.report.schedule;

import com.medfund.finance.report.entity.ReportJob;
import com.medfund.finance.report.repository.ReportJobRepository;
import com.medfund.shared.report.ReportKey;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Phase 17 §A.2 — re-fires a previously scheduled run under the invoking
 * human's identity. Looks up the original {@code report_job} row, synthesises
 * a {@link ScheduledFireContext} that mirrors it, and hands off to
 * {@link ScheduledReportOrchestrator#fireOnce}.
 *
 * <p>The partial UNIQUE index on {@code (tenant_id, report_key, schedule_id,
 * period_start)} will reject a rerun for the same period — the orchestrator
 * catches the {@code DuplicateKeyException} silently, which is the wrong
 * signal for a human-triggered rerun; the caller UX in Phase 8 shows a
 * "run already exists" toast when the poll finds no new job row.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduledReportRerunService {

    private final ReportJobRepository reportJobRepository;
    private final ScheduledReportOrchestrator orchestrator;
    private final Clock clock;

    public Mono<ReportJob> rerun(UUID jobId, String actorId, String actorEmail) {
        return reportJobRepository.findById(jobId)
                .switchIfEmpty(Mono.error(new NoSuchElementException("Job not found: " + jobId)))
                .flatMap(source -> {
                    if (source.getScheduleId() == null || source.getPeriodStart() == null
                            || source.getPeriodEnd() == null) {
                        return Mono.error(new IllegalArgumentException(
                                "Only scheduled runs are re-runnable: " + jobId));
                    }
                    ReportKey key = ReportKey.parse(source.getReportKey())
                            .orElseThrow(() -> new IllegalStateException(
                                    "Unknown report_key on job " + jobId + ": " + source.getReportKey()));
                    UUID actorUuid = parseActor(actorId);
                    TenantScheduleFireCandidate candidate = new TenantScheduleFireCandidate(
                            source.getScheduleId(),
                            source.getTenantId(),
                            source.getReportKey(),
                            null,
                            null, null, null,
                            null,
                            actorUuid,
                            actorEmail,
                            ZoneId.systemDefault().getId(),
                            null, null);
                    OffsetDateTime firedAt = OffsetDateTime.now(clock);
                    ScheduledFireContext ctx = new ScheduledFireContext(
                            source.getTenantId(),
                            key,
                            source.getScheduleId(),
                            source.getPeriodStart(),
                            source.getPeriodEnd(),
                            source.getPeriodEnd(),
                            "USD",
                            "Manual rerun",
                            firedAt,
                            actorUuid,
                            actorEmail);
                    log.info("[scheduled-report-rerun] rerun of job {} by {} → new fire for schedule {}",
                            jobId, actorEmail, source.getScheduleId());
                    return orchestrator.fireOnce(candidate, firedAt).thenReturn(source);
                });
    }

    private static UUID parseActor(String actorId) {
        if (actorId == null) return null;
        try {
            return UUID.fromString(actorId);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @SuppressWarnings("unused")
    private static ZoneId zoneOrDefault(String zone) {
        if (zone == null) return ZoneId.systemDefault();
        try {
            return ZoneId.of(zone);
        } catch (DateTimeException e) {
            return ZoneId.systemDefault();
        }
    }
}

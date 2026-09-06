package com.medfund.finance.report.schedule.controller;

import com.medfund.finance.report.entity.ReportJob;
import com.medfund.finance.report.schedule.ScheduledReportProbe;
import com.medfund.finance.report.schedule.ScheduledReportRerunService;
import com.medfund.finance.report.schedule.ScheduledReportOrchestrator;
import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/reports/scheduled")
@Tag(name = "Scheduled report rerun", description = "Phase 17 §A.2 manual rerun of scheduled fires")
@RequiredArgsConstructor
public class ScheduledReportRerunController {

    private final ScheduledReportRerunService rerunService;
    private final ScheduledReportProbe probe;
    private final ScheduledReportOrchestrator orchestrator;

    @PostMapping("/{jobId}/rerun")
    @Operation(summary = "Re-fire a previously scheduled run for the same period.",
            description = "Re-uses the source job's tenant/report/schedule/period; runs under the invoking human's actor. "
                    + "Returns 200 with the source job row (the new fire is asynchronous - poll the schedule "
                    + "history endpoint for the fresh job).")
    @RequiresPermission({"tenant.settings:manage_report_schedules",
            "tenant.settings:tenant_admin"})
    public Mono<ReportJob> rerun(@PathVariable UUID jobId,
                                 @AuthenticationPrincipal Jwt jwt) {
        return rerunService.rerun(jobId, AuditActor.id(jwt), AuditActor.email(jwt));
    }

    /**
     * Test-only endpoint (guarded by
     * {@code scheduled.report.probe.force-fire-enabled=true}) that lets
     * Playwright specs trigger a fire without waiting for the cron.
     * Never enable in production.
     */
    @PostMapping("/probe/force-fire")
    @Operation(summary = "Test-only: force the probe to enumerate + fire candidates now.")
    @ConditionalOnProperty(name = "scheduled.report.probe.force-fire-enabled", havingValue = "true")
    @RequiresPermission({"tenant.settings:tenant_admin"})
    public Mono<ResponseEntity<Long>> forceFire(@RequestParam(required = false) UUID scheduleId,
                                                @AuthenticationPrincipal Jwt jwt) {
        OffsetDateTime firedAt = OffsetDateTime.now();
        log.warn("[scheduled-report] force-fire invoked by {} at {} (scheduleId filter={})",
                AuditActor.email(jwt), firedAt, scheduleId);
        return probe.candidatesFor(firedAt)
                .filter(cand -> scheduleId == null || scheduleId.equals(cand.scheduleId()))
                .flatMap(cand -> orchestrator.fireOnce(cand, firedAt).then(Mono.just(1L)),
                        4)
                .reduce(0L, Long::sum)
                .map(count -> ResponseEntity.status(HttpStatus.OK).body(count));
    }
}

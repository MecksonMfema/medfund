package com.medfund.finance.report.schedule.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.finance.report.entity.ReportJob;
import com.medfund.finance.report.repository.ReportJobRepository;
import com.medfund.finance.report.schedule.ReportPayloadStore;
import com.medfund.finance.report.schedule.dto.ScheduledRunResponse;
import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.security.RequiresPermission;
import com.medfund.shared.security.SecurityEventPublisher;
import com.medfund.shared.tenant.TenantContext;
import io.r2dbc.postgresql.codec.Json;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 17 §C.1 — read-side history + in-app download for the tenant-admin
 * schedule detail accordion. Distinct from
 * {@link ScheduledReportDownloadController}: those endpoints authenticate via
 * an HMAC token minted into a delivery email; here we're an authenticated
 * in-app surface consumed by the Angular page, so JWT + permission gate is
 * the auth story and the tenant filter is the standard {@code X-Tenant-ID}
 * header flow.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/reports/scheduled")
@Tag(name = "Scheduled report history",
        description = "Phase 17 §C.1 tenant-admin run-history + in-app download")
@RequiredArgsConstructor
public class ScheduledReportHistoryController {

    private static final String CONTENT_TYPE_XLSX =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final int MAX_HISTORY_LIMIT = 200;

    private final ReportJobRepository reportJobRepository;
    private final Optional<ReportPayloadStore> payloadStore;
    private final SecurityEventPublisher securityEventPublisher;
    private final ObjectMapper objectMapper;

    @GetMapping("/schedules/{scheduleId}/runs")
    @Operation(summary = "Last N scheduled-fire history rows for a schedule",
            description = "Ordered by requestedAt DESC. Slim projection — no params/result blobs.")
    @RequiresPermission({"tenant.settings:manage_report_schedules",
            "admin:manage_settings", "finance:view"})
    public Flux<ScheduledRunResponse> history(@PathVariable UUID scheduleId,
                                              @RequestParam(defaultValue = "20") int limit) {
        int capped = Math.max(1, Math.min(limit, MAX_HISTORY_LIMIT));
        return Flux.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            if (tenantId == null) {
                return Flux.error(new IllegalStateException("Missing tenant context"));
            }
            return reportJobRepository.findByScheduleIdOrderedDesc(
                            UUID.fromString(tenantId), scheduleId, capped)
                    .map(job -> ScheduledRunResponse.from(job, hasXlsx(job)));
        });
    }

    @GetMapping("/runs/{jobId}/download")
    @Operation(summary = "In-app XLSX download for a scheduled run",
            description = "Serves the MinIO-backed XLSX for a completed scheduled fire. "
                    + "Tenant-scoped via the X-Tenant-ID header; emits a DATA_ACCESS "
                    + "security event attributed to the invoking admin.")
    @RequiresPermission({"tenant.settings:manage_report_schedules",
            "admin:manage_settings", "finance:view"})
    public Mono<ResponseEntity<byte[]>> download(@PathVariable UUID jobId,
                                                 @AuthenticationPrincipal Jwt jwt) {
        if (payloadStore.isEmpty()) {
            log.error("[schedule-history] MinIO not configured; cannot serve job={}", jobId);
            return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build());
        }
        ReportPayloadStore store = payloadStore.get();
        String actorId = AuditActor.id(jwt);
        String actorEmail = AuditActor.email(jwt);
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            if (tenantId == null) {
                return Mono.error(new IllegalStateException("Missing tenant context"));
            }
            return reportJobRepository.findById(jobId)
                    .filter(job -> UUID.fromString(tenantId).equals(job.getTenantId()))
                    .filter(job -> job.getScheduleId() != null)
                    .filter(job -> "completed".equalsIgnoreCase(job.getStatus()))
                    .flatMap(job -> serveXlsx(job, store, actorId, actorEmail))
                    .switchIfEmpty(Mono.just(ResponseEntity.notFound().build()));
        });
    }

    private Mono<ResponseEntity<byte[]>> serveXlsx(ReportJob job,
                                                   ReportPayloadStore store,
                                                   String actorId,
                                                   String actorEmail) {
        String xlsxRef = extractXlsxRef(job.getResultJson());
        if (xlsxRef == null || xlsxRef.isBlank()) {
            log.error("[schedule-history] job={} has no xlsxRef in result_json", job.getJobId());
            return Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
        }
        return store.getXlsx(xlsxRef)
                .flatMap(bytes -> securityEventPublisher.publishDataAccess(
                                job.getTenantId().toString(),
                                actorId,
                                actorEmail,
                                job.getReportKey(),
                                Map.of(
                                        "source", "SCHEDULED_IN_APP_DOWNLOAD",
                                        "jobId", job.getJobId().toString(),
                                        "scheduleId", job.getScheduleId() != null
                                                ? job.getScheduleId().toString() : "",
                                        "sizeBytes", bytes.length))
                        .thenReturn(ResponseEntity.ok()
                                .contentType(MediaType.parseMediaType(CONTENT_TYPE_XLSX))
                                .header(HttpHeaders.CONTENT_DISPOSITION,
                                        "attachment; filename=\"" + filenameFor(job) + "\"")
                                .body(bytes)))
                .onErrorResume(err -> {
                    log.error("[schedule-history] fetch failure for job={} xlsxRef={}",
                            job.getJobId(), xlsxRef, err);
                    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());
                });
    }

    private boolean hasXlsx(ReportJob job) {
        return "completed".equalsIgnoreCase(job.getStatus())
                && extractXlsxRef(job.getResultJson()) != null;
    }

    String extractXlsxRef(Json resultJson) {
        if (resultJson == null) return null;
        try {
            JsonNode node = objectMapper.readTree(resultJson.asString());
            JsonNode ref = node.path("xlsxRef");
            return ref.isMissingNode() || ref.isNull() ? null : ref.asText();
        } catch (Exception e) {
            log.warn("[schedule-history] parse result_json failed", e);
            return null;
        }
    }

    static String filenameFor(ReportJob job) {
        String base = job.getReportKey() != null ? job.getReportKey().toLowerCase(Locale.ROOT) : "report";
        String start = job.getPeriodStart() != null ? job.getPeriodStart().toString() : null;
        String end = job.getPeriodEnd() != null ? job.getPeriodEnd().toString() : null;
        if (start != null && end != null && !start.equals(end)) {
            return base + "_" + start + "_" + end + ".xlsx";
        }
        if (start != null) return base + "_" + start + ".xlsx";
        return base + ".xlsx";
    }
}

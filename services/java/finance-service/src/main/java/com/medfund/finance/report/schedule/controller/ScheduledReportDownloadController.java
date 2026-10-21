package com.medfund.finance.report.schedule.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.finance.report.entity.ReportJob;
import com.medfund.finance.report.repository.ReportJobRepository;
import com.medfund.finance.report.schedule.ReportPayloadStore;
import com.medfund.finance.report.schedule.download.ScheduledDownloadTokenClaims;
import com.medfund.finance.report.schedule.download.ScheduledDownloadTokenVerifier;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 17 §B.2 — public download route for scheduled-report XLSX blobs.
 * The URL is minted into delivery emails by notification-service (see
 * {@code SignedURLBuilder} in {@code services/go/notification-service/internal/report})
 * and hits this endpoint via the gateway (JWT bypass in
 * {@code services/go/gateway/internal/middleware/jwt.go}). Authorisation is
 * carried entirely by the HMAC-signed {@code token} query param — the token
 * binds (jobId, tenantId, recipientEmail, exp). {@link ScheduledDownloadTokenVerifier}
 * uses the same {@code SCHEDULED_REPORT_DOWNLOAD_TOKEN_SECRET} as the Go signer.
 *
 * <p>On every successful download we emit a {@code DATA_ACCESS} security event
 * (Rule 9) attributed to the recipient email — the recipient is not an
 * authenticated user but their identity is bound into the token by the
 * schedule owner and audited on every fetch.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/reports/scheduled")
@Tag(name = "Scheduled report download",
        description = "Phase 17 §B.2 signed-link download from the delivery email")
@RequiredArgsConstructor
public class ScheduledReportDownloadController {

    private static final String CONTENT_TYPE_XLSX =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final ScheduledDownloadTokenVerifier verifier;
    private final ReportJobRepository reportJobRepository;
    private final Optional<ReportPayloadStore> payloadStore;
    private final SecurityEventPublisher securityEventPublisher;
    private final ObjectMapper objectMapper;

    @GetMapping("/{jobId}/download")
    @Operation(summary = "Signed-link download for scheduled report XLSX (from email)",
            description = "Public route - auth via the HMAC token minted at delivery time. "
                    + "Rejects on bad/expired token, jobId mismatch, non-completed job, "
                    + "or missing MinIO object. Emits a DATA_ACCESS security event on 200.")
    public Mono<ResponseEntity<byte[]>> download(@PathVariable UUID jobId,
                                                 @RequestParam String token) {
        Optional<ScheduledDownloadTokenClaims> verifiedOpt = verifier.verify(token);
        if (verifiedOpt.isEmpty()) {
            log.info("[scheduled-download] token verify failed for job={}", jobId);
            return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN).build());
        }
        ScheduledDownloadTokenClaims claims = verifiedOpt.get();
        if (!claims.jobId().equals(jobId)) {
            log.info("[scheduled-download] jobId mismatch: pathJob={} tokenJob={}",
                    jobId, claims.jobId());
            return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN).build());
        }
        if (payloadStore.isEmpty()) {
            log.error("[scheduled-download] MinIO not configured; cannot serve job={}", jobId);
            return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build());
        }
        ReportPayloadStore store = payloadStore.get();
        // Tenant context is normally set by TenantWebFilter from the
        // X-Tenant-ID header, but this route bypasses that filter (Phase 17
        // §B.2). Populate the reactive context here from the verified token
        // so R2DBC picks the correct tenant schema for the report_job lookup.
        return reportJobRepository.findById(jobId)
                .filter(job -> claims.tenantId().equals(job.getTenantId()))
                .filter(job -> "completed".equalsIgnoreCase(job.getStatus()))
                .flatMap(job -> serveXlsx(job, claims, store))
                .switchIfEmpty(Mono.just(ResponseEntity.notFound().build()))
                .contextWrite(ctx -> TenantContext.put(ctx, claims.tenantId().toString()));
    }

    private Mono<ResponseEntity<byte[]>> serveXlsx(ReportJob job,
                                                   ScheduledDownloadTokenClaims claims,
                                                   ReportPayloadStore store) {
        String xlsxRef = extractXlsxRef(job.getResultJson());
        if (xlsxRef == null || xlsxRef.isBlank()) {
            log.error("[scheduled-download] job={} has no xlsxRef in result_json", job.getJobId());
            return Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
        }
        return store.getXlsx(xlsxRef)
                .flatMap(bytes -> securityEventPublisher.publishDataAccess(
                                job.getTenantId().toString(),
                                claims.recipientEmail(),
                                claims.recipientEmail(),
                                job.getReportKey(),
                                Map.of(
                                        "source", "SCHEDULED_LINK_DOWNLOAD",
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
                    log.error("[scheduled-download] fetch failure for job={} xlsxRef={}",
                            job.getJobId(), xlsxRef, err);
                    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());
                });
    }

    String extractXlsxRef(Json resultJson) {
        if (resultJson == null) return null;
        try {
            JsonNode node = objectMapper.readTree(resultJson.asString());
            JsonNode ref = node.path("xlsxRef");
            return ref.isMissingNode() || ref.isNull() ? null : ref.asText();
        } catch (Exception e) {
            log.warn("[scheduled-download] parse result_json failed", e);
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

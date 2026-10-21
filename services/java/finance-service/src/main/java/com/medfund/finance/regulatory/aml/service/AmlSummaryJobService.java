package com.medfund.finance.regulatory.aml.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.finance.regulatory.aml.AmlXlsxService;
import com.medfund.finance.regulatory.aml.dto.AmlSummaryJobSubmissionResponse;
import com.medfund.finance.regulatory.aml.dto.AmlSummaryReportRequest;
import com.medfund.finance.regulatory.entity.RegulatorySubmission;
import com.medfund.finance.regulatory.service.RegulatoryReportData;
import com.medfund.finance.regulatory.service.RegulatoryReportShapingService;
import com.medfund.finance.regulatory.service.RegulatorySubmissionService;
import com.medfund.finance.report.entity.ReportJob;
import com.medfund.finance.report.repository.ReportJobRepository;
import com.medfund.finance.report.service.ReportJobService;
import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.report.ReportKey;
import io.r2dbc.postgresql.codec.Json;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Orchestrates the AML/STR periodic summary async job lifecycle. Same
 * shape as {@code PmbSpendJobService} (Phase 18) — country-native
 * currency, in-process compute (no ai-service round-trip), optional
 * archive under {@code regulatory_submission} on {@code submit=true}.
 *
 * <p>Retention: {@code ReportJobService.classifyRetention} returns
 * {@code STATUTORY_7Y} because {@code AML_STR} lives under
 * {@code ReportFamily.COMPLIANCE} (Phase 7 REG19).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AmlSummaryJobService {

    public static final String STATUS_REQUESTED = "requested";
    public static final String STATUS_PROCESSING = "processing";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_FAILED = "failed";
    public static final String RESULT_SUBMISSION_ID = "submissionId";
    public static final String RESULT_SECTIONS = "sections";

    private static final UUID UUID_NULL = new UUID(0L, 0L);

    private final ReportJobRepository jobRepository;
    private final RegulatoryReportShapingService shapingService;
    private final AmlXlsxService xlsxService;
    private final RegulatorySubmissionService submissionService;
    private final AuditPublisher auditPublisher;
    private final ObjectMapper objectMapper;

    public Mono<AmlSummaryJobSubmissionResponse> submit(AmlSummaryReportRequest request,
                                                        UUID tenantId,
                                                        Jwt jwt,
                                                        String actorId,
                                                        String actorEmail) {
        return Mono.defer(() -> {
            RegulatoryReportShapingService.rejectClientCurrencyOverride(
                    ReportKey.AML_STR, request.reportingCurrency());
            Map<String, Object> params = buildParams(request);
            String paramsHash = hash(params);
            return jobRepository
                    .findFirstByTenantIdAndParamsHashAndStatusInOrderByRequestedAtDesc(
                            tenantId, paramsHash, List.of(STATUS_REQUESTED, STATUS_PROCESSING))
                    .map(existing -> new AmlSummaryJobSubmissionResponse(
                            existing.getJobId(), existing.getStatus(), true))
                    .switchIfEmpty(Mono.defer(() -> insertAndKickoff(request, tenantId, jwt,
                            actorId, actorEmail, params, paramsHash)));
        });
    }

    private Mono<AmlSummaryJobSubmissionResponse> insertAndKickoff(AmlSummaryReportRequest request,
                                                                    UUID tenantId,
                                                                    Jwt jwt,
                                                                    String actorId, String actorEmail,
                                                                    Map<String, Object> params,
                                                                    String paramsHash) {
        ReportJob row = new ReportJob();
        // Leave jobId null — DB fills via gen_random_uuid()
        // (bug_r2dbc_pre_populated_id_update_mode).
        row.setTenantId(tenantId);
        row.setReportKey(ReportKey.AML_STR.name());
        row.setStatus(STATUS_REQUESTED);
        row.setParamsJson(jsonOf(params));
        row.setParamsHash(paramsHash);
        row.setRequestedAt(OffsetDateTime.now());
        if (actorId != null) {
            try {
                row.setRequestedBy(UUID.fromString(actorId));
            } catch (IllegalArgumentException ignored) {
                // Non-UUID subject — actorEmail carries the trail.
            }
        }
        row.setRequestedByEmail(actorEmail);
        row.setRetentionClass(ReportJobService.classifyRetention(row.getReportKey()));
        return jobRepository.save(row)
                .flatMap(saved -> auditSubmit(saved, actorId, actorEmail).thenReturn(saved))
                .map(saved -> {
                    // Capture the response BEFORE fanning out compute — the
                    // async chain mutates saved.status and would race the read.
                    AmlSummaryJobSubmissionResponse resp = new AmlSummaryJobSubmissionResponse(
                            saved.getJobId(), saved.getStatus(), false);
                    scheduleCompute(saved, request, tenantId, jwt, actorId, actorEmail);
                    return resp;
                });
    }

    private void scheduleCompute(ReportJob saved, AmlSummaryReportRequest request,
                                 UUID tenantId, Jwt jwt,
                                 String actorId, String actorEmail) {
        computeChain(saved, request, tenantId, jwt, actorId, actorEmail)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        completed -> log.info("[aml-summary-job] {} completed status={}",
                                saved.getJobId(), completed.getStatus()),
                        err -> log.error("[aml-summary-job] {} unhandled error on compute chain: {}",
                                saved.getJobId(), err.getMessage(), err));
    }

    /** Package-private test seam — drives the compute chain without the scheduler. */
    Mono<ReportJob> computeChain(ReportJob saved, AmlSummaryReportRequest request,
                                 UUID tenantId, Jwt jwt,
                                 String actorId, String actorEmail) {
        return markProcessing(saved)
                .then(shapingService.shape(ReportKey.AML_STR,
                        tenantId, request.periodStart(), request.periodEnd()))
                .flatMap(data -> maybeArchive(data, request, tenantId, jwt, actorId, actorEmail, saved)
                        .flatMap(submissionId -> writeCompleted(saved, data, submissionId)))
                .onErrorResume(err -> markFailed(saved, err));
    }

    private Mono<ReportJob> markProcessing(ReportJob saved) {
        saved.setStatus(STATUS_PROCESSING);
        return jobRepository.save(saved);
    }

    private Mono<UUID> maybeArchive(RegulatoryReportData data, AmlSummaryReportRequest request,
                                     UUID tenantId, Jwt jwt,
                                     String actorId, String actorEmail,
                                     ReportJob saved) {
        if (!request.submit()) return Mono.just(UUID_NULL);
        return xlsxService.render(tenantId, data)
                .flatMap(rendered -> submissionService.submit(
                                tenantId,
                                ReportKey.AML_STR.name(),
                                data.periodStart(),
                                data.periodEnd(),
                                saved.getJobId(),
                                rendered.bytes(),
                                jwt,
                                actorId,
                                actorEmail,
                                request.attestationNote(),
                                null)
                        .map(RegulatorySubmission::getId));
    }

    private Mono<ReportJob> writeCompleted(ReportJob saved, RegulatoryReportData data, UUID submissionId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reportKey", data.reportKey().name());
        result.put("tenantId", data.tenantId() != null ? data.tenantId().toString() : null);
        result.put("periodStart", data.periodStart().toString());
        result.put("periodEnd", data.periodEnd().toString());
        result.put("reportingCurrency", data.reportingCurrency());
        result.put(RESULT_SECTIONS, data.sections());
        if (submissionId != null && !UUID_NULL.equals(submissionId)) {
            result.put(RESULT_SUBMISSION_ID, submissionId.toString());
        }
        saved.setResultJson(jsonOf(result));
        saved.setStatus(STATUS_COMPLETED);
        saved.setCompletedAt(OffsetDateTime.now());
        return jobRepository.save(saved);
    }

    private Mono<ReportJob> markFailed(ReportJob saved, Throwable err) {
        log.error("[aml-summary-job] {} shape/archive failure: {}",
                saved.getJobId(), err.getMessage(), err);
        saved.setStatus(STATUS_FAILED);
        saved.setErrorMessage(err.getMessage() != null ? err.getMessage() : err.getClass().getSimpleName());
        saved.setCompletedAt(OffsetDateTime.now());
        return jobRepository.save(saved);
    }

    private Mono<Void> auditSubmit(ReportJob saved, String actorId, String actorEmail) {
        return auditPublisher.publish(AuditEvent.create(
                saved.getTenantId().toString(),
                "AmlSummaryReportJob",
                saved.getJobId().toString(),
                ReportKey.AML_STR.name(),
                "CREATE",
                actorId != null ? actorId : AuditActor.SYSTEM_ID,
                actorEmail != null ? actorEmail : AuditActor.SYSTEM_EMAIL,
                null,
                Map.of(
                        "reportKey", saved.getReportKey(),
                        "paramsHash", saved.getParamsHash(),
                        "retentionClass", saved.getRetentionClass()),
                new String[]{"reportKey", "paramsHash"},
                UUID.randomUUID().toString()));
    }

    /** Re-shape from the persisted params — idempotent for closed periods. */
    public Mono<RegulatoryReportData> reshapeFromJob(ReportJob job) {
        LocalDate periodStart = extractLocalDate(job.getParamsJson(), "periodStart");
        LocalDate periodEnd = extractLocalDate(job.getParamsJson(), "periodEnd");
        if (periodStart == null || periodEnd == null) {
            return Mono.error(new IllegalStateException(
                    "AML Summary job " + job.getJobId() + " params missing periodStart/periodEnd"));
        }
        return shapingService.shape(ReportKey.AML_STR,
                job.getTenantId(), periodStart, periodEnd);
    }

    public Mono<ReportJob> get(UUID jobId, UUID tenantId) {
        return jobRepository.findById(jobId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "AML Summary job not found: " + jobId)))
                .flatMap(job -> {
                    if (tenantId != null && !job.getTenantId().equals(tenantId)) {
                        return Mono.error(new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "AML Summary job not found: " + jobId));
                    }
                    if (!ReportKey.AML_STR.name().equals(job.getReportKey())) {
                        return Mono.error(new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Job " + jobId + " is not an AML Summary report"));
                    }
                    return Mono.just(job);
                });
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private Map<String, Object> buildParams(AmlSummaryReportRequest request) {
        Map<String, Object> params = new TreeMap<>();
        params.put("reportKey", ReportKey.AML_STR.name());
        params.put("periodStart", request.periodStart().toString());
        params.put("periodEnd", request.periodEnd().toString());
        params.put("reportingCurrency", "COUNTRY_NATIVE");
        return params;
    }

    private String hash(Map<String, Object> params) {
        try {
            String canonical = objectMapper.writeValueAsString(new TreeMap<>(params));
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Failed to hash AML Summary params", e);
        }
    }

    private Json jsonOf(Map<String, Object> value) {
        try {
            return Json.of(objectMapper.writeValueAsString(value));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialise AML Summary params for job row", e);
        }
    }

    private LocalDate extractLocalDate(Json json, String key) {
        if (json == null) return null;
        try {
            Map<?, ?> map = objectMapper.readValue(json.asString(), Map.class);
            Object v = map.get(key);
            if (v == null) return null;
            return LocalDate.parse(v.toString());
        } catch (Exception e) {
            log.warn("[aml-summary-job] failed to extract {} from params: {}", key, e.getMessage());
            return null;
        }
    }
}

package com.medfund.finance.regulatory.ipec.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.finance.regulatory.entity.RegulatorySubmission;
import com.medfund.finance.regulatory.ipec.IpecXlsxService;
import com.medfund.finance.regulatory.ipec.dto.IpecJobSubmissionResponse;
import com.medfund.finance.regulatory.ipec.dto.IpecReportRequest;
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
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
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
 * Orchestrates the IPEC quarterly return job lifecycle. Follows the
 * Phase 15 async {@code report_job} pattern — submit inserts a
 * {@code requested} row + a fire-and-forget shape → serialise result →
 * flip to {@code completed} (or {@code failed}) chain — but the compute
 * is entirely in-process (no ai-service Kafka round-trip) because the
 * IPEC return is pure aggregation.
 *
 * <p>The persisted {@code result_json} carries the shaped
 * {@link RegulatoryReportData} sections + a {@code submissionId} slot
 * populated when {@code request.submit()} is true. The XLSX endpoint
 * re-composes deterministically from the persisted params + a live
 * re-shape (compute is cheap, storage is small).
 *
 * <p>Retention: {@link ReportJobService#classifyRetention} returns
 * {@code STATUTORY_7Y} for the IPEC key because Phase 7 REG19 placed
 * {@code IPEC_QUARTERLY_RETURN} under {@code ReportFamily.PRUDENTIAL},
 * which the classifier maps to statutory retention (F-REG1).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IpecJobService {

    public static final String STATUS_REQUESTED = "requested";
    public static final String STATUS_PROCESSING = "processing";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_FAILED = "failed";
    /** Slot under {@link ReportJob#getResultJson} where the submission id lands after archive. */
    public static final String RESULT_SUBMISSION_ID = "submissionId";
    /** Slot under {@link ReportJob#getResultJson} where the shaped sections land for the Angular summary. */
    public static final String RESULT_SECTIONS = "sections";

    /** Sentinel used to signal "no submission archived" through the Mono chain. */
    private static final UUID UUID_NULL = new UUID(0L, 0L);

    private final ReportJobRepository jobRepository;
    private final RegulatoryReportShapingService shapingService;
    private final IpecXlsxService xlsxService;
    private final RegulatorySubmissionService submissionService;
    private final AuditPublisher auditPublisher;
    private final ObjectMapper objectMapper;

    /**
     * Insert a {@code report_job} row for the requested IPEC quarter,
     * spawn the async compute chain, and return the jobId immediately.
     * Duplicate in-flight jobs (same tenant + params_hash) reuse the
     * existing row per the dedupe convention.
     */
    public Mono<IpecJobSubmissionResponse> submit(IpecReportRequest request,
                                                  UUID tenantId,
                                                  Jwt jwt,
                                                  String actorId,
                                                  String actorEmail) {
        return Mono.defer(() -> {
            RegulatoryReportShapingService.rejectClientCurrencyOverride(
                    ReportKey.IPEC_QUARTERLY_RETURN, request.reportingCurrency());
            Map<String, Object> params = buildParams(request);
            String paramsHash = hash(params);
            return jobRepository
                    .findFirstByTenantIdAndParamsHashAndStatusInOrderByRequestedAtDesc(
                            tenantId, paramsHash, List.of(STATUS_REQUESTED, STATUS_PROCESSING))
                    .map(existing -> new IpecJobSubmissionResponse(
                            existing.getJobId(), existing.getStatus(), true))
                    .switchIfEmpty(Mono.defer(() -> insertAndKickoff(request, tenantId, jwt,
                            actorId, actorEmail, params, paramsHash)));
        });
    }

    private Mono<IpecJobSubmissionResponse> insertAndKickoff(IpecReportRequest request,
                                                             UUID tenantId,
                                                             Jwt jwt,
                                                             String actorId, String actorEmail,
                                                             Map<String, Object> params,
                                                             String paramsHash) {
        ReportJob row = new ReportJob();
        // Leave jobId null — DB fills via gen_random_uuid(); pre-populated Id
        // makes R2DBC treat the save as an UPDATE (bug_r2dbc_pre_populated_id_update_mode).
        row.setTenantId(tenantId);
        row.setReportKey(ReportKey.IPEC_QUARTERLY_RETURN.name());
        row.setStatus(STATUS_REQUESTED);
        row.setParamsJson(jsonOf(params));
        row.setParamsHash(paramsHash);
        row.setRequestedAt(OffsetDateTime.now());
        if (actorId != null) {
            try {
                row.setRequestedBy(UUID.fromString(actorId));
            } catch (IllegalArgumentException ignored) {
                // Non-UUID subject; actorEmail carries the trail.
            }
        }
        row.setRequestedByEmail(actorEmail);
        row.setRetentionClass(ReportJobService.classifyRetention(row.getReportKey()));
        return jobRepository.save(row)
                .flatMap(saved -> auditSubmit(saved, actorId, actorEmail).thenReturn(saved))
                .map(saved -> {
                    // Capture the response BEFORE fanning out the async compute — the
                    // compute chain mutates the same saved instance (status → processing
                    // → completed / failed) and would race with our .getStatus() read.
                    IpecJobSubmissionResponse resp = new IpecJobSubmissionResponse(
                            saved.getJobId(), saved.getStatus(), false);
                    scheduleCompute(saved, request, tenantId, jwt, actorId, actorEmail);
                    return resp;
                });
    }

    /**
     * Fire-and-forget the shape → serialise → maybe-archive chain on the
     * bounded elastic scheduler. Errors flip the job to {@code failed}
     * with the exception message on {@code error_message}. The caller
     * has already returned the jobId; polling picks up the terminal state.
     */
    private void scheduleCompute(ReportJob saved, IpecReportRequest request,
                                 UUID tenantId, Jwt jwt,
                                 String actorId, String actorEmail) {
        computeChain(saved, request, tenantId, jwt, actorId, actorEmail)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        completed -> log.info("[ipec-job] {} completed status={}",
                                saved.getJobId(), completed.getStatus()),
                        err -> log.error("[ipec-job] {} unhandled error on compute chain: {}",
                                saved.getJobId(), err.getMessage(), err));
    }

    /** Package-private for test drive-through of the compute chain without the scheduler. */
    Mono<ReportJob> computeChain(ReportJob saved, IpecReportRequest request,
                                 UUID tenantId, Jwt jwt,
                                 String actorId, String actorEmail) {
        return markProcessing(saved)
                .then(shapingService.shape(ReportKey.IPEC_QUARTERLY_RETURN,
                        tenantId, request.periodStart(), request.periodEnd()))
                .flatMap(data -> maybeArchive(data, request, tenantId, jwt, actorId, actorEmail, saved)
                        .flatMap(submissionId -> writeCompleted(saved, data, submissionId)))
                .onErrorResume(err -> markFailed(saved, err));
    }

    private Mono<ReportJob> markProcessing(ReportJob saved) {
        saved.setStatus(STATUS_PROCESSING);
        return jobRepository.save(saved);
    }

    private Mono<UUID> maybeArchive(RegulatoryReportData data, IpecReportRequest request,
                                     UUID tenantId, Jwt jwt,
                                     String actorId, String actorEmail,
                                     ReportJob saved) {
        if (!request.submit()) return Mono.just(UUID_NULL);
        return xlsxService.render(tenantId, data)
                .flatMap(rendered -> submissionService.submit(
                                tenantId,
                                ReportKey.IPEC_QUARTERLY_RETURN.name(),
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
        log.error("[ipec-job] {} shape/archive failure: {}", saved.getJobId(), err.getMessage(), err);
        saved.setStatus(STATUS_FAILED);
        saved.setErrorMessage(err.getMessage() != null ? err.getMessage() : err.getClass().getSimpleName());
        saved.setCompletedAt(OffsetDateTime.now());
        return jobRepository.save(saved);
    }

    private Mono<Void> auditSubmit(ReportJob saved, String actorId, String actorEmail) {
        return auditPublisher.publish(AuditEvent.create(
                saved.getTenantId().toString(),
                "IpecReportJob",
                saved.getJobId().toString(),
                ReportKey.IPEC_QUARTERLY_RETURN.name(),
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

    /**
     * Rebuild the {@link RegulatoryReportData} used by the XLSX endpoint
     * — always re-shape rather than deserialise the persisted result. IPEC
     * shape is idempotent for closed periods, and re-shape guarantees the
     * XLSX reflects any late-arriving corrections (e.g. an adjustment
     * posted after the initial submit).
     */
    public Mono<RegulatoryReportData> reshapeFromJob(ReportJob job) {
        LocalDate periodStart = extractLocalDate(job.getParamsJson(), "periodStart");
        LocalDate periodEnd = extractLocalDate(job.getParamsJson(), "periodEnd");
        if (periodStart == null || periodEnd == null) {
            return Mono.error(new IllegalStateException(
                    "IPEC job " + job.getJobId() + " params missing periodStart/periodEnd"));
        }
        return shapingService.shape(ReportKey.IPEC_QUARTERLY_RETURN,
                job.getTenantId(), periodStart, periodEnd);
    }

    /** Retrieve a job with the Rule-2 tenant guard applied. 404 on cross-tenant. */
    public Mono<ReportJob> get(UUID jobId, UUID tenantId) {
        return jobRepository.findById(jobId)
                .switchIfEmpty(Mono.error(new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND,
                        "IPEC job not found: " + jobId)))
                .flatMap(job -> {
                    if (tenantId != null && !job.getTenantId().equals(tenantId)) {
                        return Mono.error(new org.springframework.web.server.ResponseStatusException(
                                org.springframework.http.HttpStatus.NOT_FOUND,
                                "IPEC job not found: " + jobId));
                    }
                    if (!ReportKey.IPEC_QUARTERLY_RETURN.name().equals(job.getReportKey())) {
                        return Mono.error(new org.springframework.web.server.ResponseStatusException(
                                org.springframework.http.HttpStatus.NOT_FOUND,
                                "Job " + jobId + " is not an IPEC quarterly return"));
                    }
                    return Mono.just(job);
                });
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private Map<String, Object> buildParams(IpecReportRequest request) {
        Map<String, Object> params = new TreeMap<>();
        params.put("reportKey", ReportKey.IPEC_QUARTERLY_RETURN.name());
        params.put("periodStart", request.periodStart().toString());
        params.put("periodEnd", request.periodEnd().toString());
        // Reporting currency is always ZWL — persist for observability, not for
        // dedupe. Submit flag doesn't participate in dedupe either (a dry-run
        // and a submit for the same period reuse the same compute).
        params.put("reportingCurrency", "ZWL");
        return params;
    }

    private String hash(Map<String, Object> params) {
        try {
            String canonical = objectMapper.writeValueAsString(new TreeMap<>(params));
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Failed to hash IPEC params", e);
        }
    }

    private Json jsonOf(Map<String, Object> value) {
        try {
            return Json.of(objectMapper.writeValueAsString(value));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialise IPEC params for job row", e);
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
            log.warn("[ipec-job] failed to extract {} from params: {}", key, e.getMessage());
            return null;
        }
    }
}

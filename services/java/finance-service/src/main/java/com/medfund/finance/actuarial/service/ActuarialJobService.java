package com.medfund.finance.actuarial.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.finance.actuarial.dto.IbnrJobRequest;
import com.medfund.finance.actuarial.dto.JobStatusResponse;
import com.medfund.finance.actuarial.dto.JobSubmissionResponse;
import com.medfund.finance.actuarial.dto.PersistencyStudyJobRequest;
import com.medfund.finance.actuarial.entity.ActuarialReportJob;
import com.medfund.finance.actuarial.kafka.ActuarialJobPublisher;
import com.medfund.finance.actuarial.service.PersistencyCohortShapingService.PersistencyShapeRequest;
import com.medfund.finance.actuarial.service.PersistencyCohortShapingService.PersistencyShapeResult;
import com.medfund.finance.actuarial.service.TriangleShapingService.TriangleShapeRequest;
import com.medfund.finance.actuarial.service.TriangleShapingService.TriangleShapeResult;
import com.medfund.shared.actuarial.ActuarialJobRequestedEvent;
import com.medfund.shared.report.ReportKey;
import com.medfund.shared.report.ReportingCurrencyResolver;
import io.r2dbc.postgresql.codec.Json;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Orchestrates the async actuarial pipeline: computes the params-hash for
 * dedupe, checks the {@code ux_arj_inflight} partial UNIQUE for an
 * outstanding job, inserts the row + publishes to Kafka. Also serves the
 * polling {@code /jobs/{jobId}} endpoint and the export lookup — every
 * caller-facing read enforces the Rule-2 tenant match against the JWT-sourced
 * tenantId.
 *
 * <p>Params-hash is SHA-256 over the JSON serialisation of the params map
 * with keys sorted deterministically — two identical requests from the same
 * tenant produce the same hex, and the partial UNIQUE constraint
 * (in-flight only) short-circuits the second one to reuse the existing row.
 * Terminal (completed / failed) jobs no longer occupy the constraint slot
 * so a retry after failure creates a fresh row rather than resurrecting
 * the failed one.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActuarialJobService {

    private final com.medfund.finance.actuarial.repository.ActuarialReportJobRepository repository;
    private final ActuarialJobPublisher publisher;
    private final TriangleShapingService triangleShapingService;
    private final PersistencyCohortShapingService persistencyShapingService;
    private final ObjectMapper objectMapper;
    private final ReportingCurrencyResolver currencyResolver;

    public Mono<JobSubmissionResponse> submit(ReportKey reportKey, IbnrJobRequest request,
                                              UUID tenantId, String actorId, String actorEmail) {
        return currencyResolver.resolve(tenantId, request.reportingCurrency())
                .flatMap(resolvedCurrency -> submitWithCurrency(
                        reportKey, request, tenantId, actorId, actorEmail, resolvedCurrency));
    }

    private Mono<JobSubmissionResponse> submitWithCurrency(ReportKey reportKey, IbnrJobRequest request,
                                                           UUID tenantId, String actorId, String actorEmail,
                                                           String resolvedCurrency) {
        Map<String, Object> params = buildParams(request, resolvedCurrency);
        String paramsHash = hash(params);

        return repository
                .findFirstByTenantIdAndParamsHashAndStatusInOrderByRequestedAtDesc(
                        tenantId, paramsHash, List.of("requested", "processing"))
                .flatMap(existing -> Mono.just(new JobSubmissionResponse(
                        existing.getJobId(), existing.getStatus(), true)))
                .switchIfEmpty(insertAndPublish(reportKey, request, tenantId,
                        actorId, actorEmail, params, paramsHash, resolvedCurrency));
    }

    private Mono<JobSubmissionResponse> insertAndPublish(ReportKey reportKey, IbnrJobRequest request,
                                                         UUID tenantId, String actorId, String actorEmail,
                                                         Map<String, Object> params, String paramsHash,
                                                         String resolvedCurrency) {
        // Leave jobId null so R2DBC does an INSERT (defaults to gen_random_uuid()).
        // Setting the PK up-front makes R2DBC treat the persist as UPDATE and fail
        // "Row with Id [...] does not exist".
        ActuarialReportJob row = new ActuarialReportJob();
        row.setTenantId(tenantId);
        row.setReportKey(reportKey.name());
        row.setStatus("requested");
        row.setParamsJson(jsonOf(params));
        row.setParamsHash(paramsHash);
        row.setRequestedAt(OffsetDateTime.now());
        if (actorId != null) {
            try {
                row.setRequestedBy(UUID.fromString(actorId));
            } catch (IllegalArgumentException ignored) {
                // Non-UUID subject (e.g. "system") — actorEmail carries the human trail.
            }
        }
        row.setRequestedByEmail(actorEmail);

        return repository.save(row)
                .flatMap(saved -> shapeAndPublish(reportKey, request, tenantId, actorId, actorEmail,
                        params, resolvedCurrency, saved));
    }

    private Mono<JobSubmissionResponse> shapeAndPublish(ReportKey reportKey, IbnrJobRequest request,
                                                        UUID tenantId, String actorId, String actorEmail,
                                                        Map<String, Object> params, String resolvedCurrency,
                                                        ActuarialReportJob saved) {
        TriangleShapeRequest shapeRequest = new TriangleShapeRequest(
                tenantId,
                request.periodStart(),
                request.periodEnd(),
                request.insuranceLine(),
                request.shape(),
                request.grain(),
                resolvedCurrency);

        return triangleShapingService.shape(shapeRequest)
                .flatMap(shape -> publishShapedJob(shape, saved, reportKey, tenantId, actorId, actorEmail, params))
                .onErrorResume(err -> markFailedInline(saved, err));
    }

    private Mono<JobSubmissionResponse> publishShapedJob(TriangleShapeResult shape, ActuarialReportJob saved,
                                                         ReportKey reportKey, UUID tenantId,
                                                         String actorId, String actorEmail,
                                                         Map<String, Object> params) {
        Map<String, Object> paramsWithWarnings = new LinkedHashMap<>(params);
        if (!shape.warnings().isEmpty()) {
            paramsWithWarnings.put("shape_warnings", shape.warnings());
        }
        // Persist the shaped triangle in params_json so the XLSX export
        // and the Angular split-view can render the input matrix. Without
        // this, both surfaces see "Triangle input not preserved" per the
        // XLSX service's fallback branch and the client has nothing to
        // heatmap.
        paramsWithWarnings.put("triangle", shape.triangle());
        saved.setParamsJson(jsonOf(paramsWithWarnings));

        ActuarialJobRequestedEvent event = new ActuarialJobRequestedEvent(
                ActuarialJobRequestedEvent.CURRENT_SCHEMA_VERSION,
                saved.getJobId(),
                tenantId,
                reportKey.name(),
                paramsWithWarnings,
                shape.triangle(),
                null,
                null,
                parseUuidOrNull(actorId),
                actorEmail);
        return repository.save(saved)
                .then(publisher.publish(event))
                .thenReturn(new JobSubmissionResponse(saved.getJobId(), saved.getStatus(), false));
    }

    private Mono<JobSubmissionResponse> markFailedInline(ActuarialReportJob saved, Throwable err) {
        log.error("[actuarial-job] pre-publish failure for job {}: {}", saved.getJobId(), err.getMessage(), err);
        saved.setStatus("failed");
        saved.setErrorMessage(err.getMessage() != null ? err.getMessage() : err.getClass().getSimpleName());
        saved.setCompletedAt(OffsetDateTime.now());
        return repository.save(saved)
                .thenReturn(new JobSubmissionResponse(saved.getJobId(), "failed", false));
    }

    // ── PERSISTENCY_STUDY (Phase 14 Phase 11) ───────────────────────────

    public Mono<JobSubmissionResponse> submitPersistencyStudy(PersistencyStudyJobRequest request,
                                                              UUID tenantId, String actorId, String actorEmail) {
        return currencyResolver.resolve(tenantId, request.reportingCurrency())
                .flatMap(resolvedCurrency -> {
                    Map<String, Object> params = buildPersistencyParams(request, resolvedCurrency);
                    String paramsHash = hash(params);
                    return repository
                            .findFirstByTenantIdAndParamsHashAndStatusInOrderByRequestedAtDesc(
                                    tenantId, paramsHash, List.of("requested", "processing"))
                            .flatMap(existing -> Mono.just(new JobSubmissionResponse(
                                    existing.getJobId(), existing.getStatus(), true)))
                            .switchIfEmpty(insertAndPublishPersistency(
                                    request, tenantId, actorId, actorEmail, params, paramsHash));
                });
    }

    private Mono<JobSubmissionResponse> insertAndPublishPersistency(PersistencyStudyJobRequest request,
                                                                    UUID tenantId, String actorId, String actorEmail,
                                                                    Map<String, Object> params, String paramsHash) {
        ActuarialReportJob row = new ActuarialReportJob();
        row.setTenantId(tenantId);
        row.setReportKey(ReportKey.PERSISTENCY_STUDY.name());
        row.setStatus("requested");
        row.setParamsJson(jsonOf(params));
        row.setParamsHash(paramsHash);
        row.setRequestedAt(OffsetDateTime.now());
        if (actorId != null) {
            try {
                row.setRequestedBy(UUID.fromString(actorId));
            } catch (IllegalArgumentException ignored) {
                // service subject — actorEmail carries the trail
            }
        }
        row.setRequestedByEmail(actorEmail);

        return repository.save(row)
                .flatMap(saved -> shapePersistencyAndPublish(request, tenantId, actorId, actorEmail,
                        params, saved));
    }

    private Mono<JobSubmissionResponse> shapePersistencyAndPublish(PersistencyStudyJobRequest request,
                                                                    UUID tenantId, String actorId, String actorEmail,
                                                                    Map<String, Object> params,
                                                                    ActuarialReportJob saved) {
        PersistencyShapeRequest shapeRequest = new PersistencyShapeRequest(
                tenantId,
                request.periodStart(),
                request.periodEnd(),
                request.checkpoints(),
                request.insuranceLine());
        return persistencyShapingService.shape(shapeRequest)
                .flatMap(shape -> publishShapedPersistency(shape, saved, tenantId, actorId, actorEmail, params))
                .onErrorResume(err -> markFailedInline(saved, err));
    }

    private Mono<JobSubmissionResponse> publishShapedPersistency(PersistencyShapeResult shape,
                                                                  ActuarialReportJob saved, UUID tenantId,
                                                                  String actorId, String actorEmail,
                                                                  Map<String, Object> params) {
        Map<String, Object> paramsWithWarnings = new LinkedHashMap<>(params);
        if (!shape.warnings().isEmpty()) {
            paramsWithWarnings.put("shape_warnings", shape.warnings());
        }
        // Persist the shaped cohort payload in params_json so the XLSX
        // export + Angular chart can render actual/expected from the same
        // source of truth the compute worked from.
        paramsWithWarnings.put("cohort", shape.cohort());
        saved.setParamsJson(jsonOf(paramsWithWarnings));

        ActuarialJobRequestedEvent event = new ActuarialJobRequestedEvent(
                ActuarialJobRequestedEvent.CURRENT_SCHEMA_VERSION,
                saved.getJobId(),
                tenantId,
                ReportKey.PERSISTENCY_STUDY.name(),
                paramsWithWarnings,
                null,
                null,
                shape.cohort(),
                parseUuidOrNull(actorId),
                actorEmail);
        return repository.save(saved)
                .then(publisher.publish(event))
                .thenReturn(new JobSubmissionResponse(saved.getJobId(), saved.getStatus(), false));
    }

    private Map<String, Object> buildPersistencyParams(PersistencyStudyJobRequest request, String resolvedCurrency) {
        Map<String, Object> params = new TreeMap<>();
        params.put("periodStart", request.periodStart().toString());
        params.put("periodEnd", request.periodEnd().toString());
        params.put("insuranceLine", request.insuranceLine() != null ? request.insuranceLine() : "ALL");
        params.put("checkpoints", request.checkpoints() != null && !request.checkpoints().isEmpty()
                ? request.checkpoints()
                : List.of(3, 6, 12, 24, 36));
        params.put("reportingCurrency", resolvedCurrency);
        return params;
    }

    public Mono<JobStatusResponse> status(UUID jobId, UUID tenantId) {
        return repository.findById(jobId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND,
                        "Actuarial job not found: " + jobId)))
                .flatMap(job -> {
                    if (tenantId != null && !job.getTenantId().equals(tenantId)) {
                        return Mono.error(new ResponseStatusException(
                                org.springframework.http.HttpStatus.NOT_FOUND,
                                "Actuarial job not found: " + jobId));
                    }
                    return Mono.just(toResponse(job));
                });
    }

    /** Internal lookup for the XLSX export path — tenant guard applied by the caller
     *  (the export endpoint) so this returns the raw row. */
    public Mono<ActuarialReportJob> get(UUID jobId, UUID tenantId) {
        return repository.findById(jobId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND,
                        "Actuarial job not found: " + jobId)))
                .flatMap(job -> {
                    if (tenantId != null && !job.getTenantId().equals(tenantId)) {
                        return Mono.error(new ResponseStatusException(
                                org.springframework.http.HttpStatus.NOT_FOUND,
                                "Actuarial job not found: " + jobId));
                    }
                    return Mono.just(job);
                });
    }

    private JobStatusResponse toResponse(ActuarialReportJob job) {
        int progressPct = switch (job.getStatus()) {
            case "requested" -> 10;
            case "processing" -> 50;
            case "completed", "failed" -> 100;
            default -> 0;
        };
        com.fasterxml.jackson.databind.JsonNode resultNode = null;
        if (job.getResultJson() != null) {
            try {
                resultNode = objectMapper.readTree(job.getResultJson().asString());
            } catch (Exception e) {
                log.warn("[actuarial-job] failed to parse result_json for job {}: {}",
                        job.getJobId(), e.getMessage());
            }
        }
        com.fasterxml.jackson.databind.JsonNode paramsNode = null;
        if (job.getParamsJson() != null) {
            try {
                paramsNode = objectMapper.readTree(job.getParamsJson().asString());
            } catch (Exception e) {
                log.warn("[actuarial-job] failed to parse params_json for job {}: {}",
                        job.getJobId(), e.getMessage());
            }
        }
        return new JobStatusResponse(
                job.getJobId(),
                job.getReportKey(),
                job.getStatus(),
                progressPct,
                paramsNode,
                resultNode,
                job.getErrorMessage(),
                job.getRequestedAt(),
                job.getCompletedAt());
    }

    private Map<String, Object> buildParams(IbnrJobRequest request, String resolvedCurrency) {
        Map<String, Object> params = new TreeMap<>();
        params.put("periodStart", request.periodStart().toString());
        params.put("periodEnd", request.periodEnd().toString());
        params.put("insuranceLine", request.insuranceLine() != null ? request.insuranceLine() : "ALL");
        params.put("shape", request.shape().name());
        params.put("grain", request.grain().name());
        params.put("reportingCurrency", resolvedCurrency);
        params.put("ldfMethod", request.ldfMethod() != null ? request.ldfMethod() : "volume");
        return params;
    }

    private String hash(Map<String, Object> params) {
        try {
            String canonical = objectMapper.writeValueAsString(new TreeMap<>(params));
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Failed to hash actuarial params", e);
        }
    }

    private Json jsonOf(Map<String, Object> value) {
        try {
            return Json.of(objectMapper.writeValueAsString(value));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialise params for job row", e);
        }
    }

    private static UUID parseUuidOrNull(String raw) {
        if (raw == null) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

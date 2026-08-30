package com.medfund.finance.ifrs17.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.finance.ifrs17.dto.Ifrs17ChunkPayload;
import com.medfund.finance.ifrs17.dto.Ifrs17JobSubmissionResponse;
import com.medfund.finance.ifrs17.dto.Ifrs17ReportRequest;
import com.medfund.finance.report.entity.ReportJob;
import com.medfund.finance.report.entity.ReportJobChunk;
import com.medfund.finance.report.kafka.ReportJobPublisher;
import com.medfund.finance.report.repository.ReportJobChunkRepository;
import com.medfund.finance.report.repository.ReportJobRepository;
import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.kafka.MinIOPayloadStore;
import com.medfund.shared.report.ReportFamily;
import com.medfund.shared.report.ReportJobRequestedEvent;
import com.medfund.shared.report.ReportKey;
import com.medfund.shared.report.ReportingCurrencyResolver;
import com.medfund.shared.tenant.TenantContext;
import io.r2dbc.postgresql.codec.Json;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Orchestrates async IFRS 17 report submission (§17). Flow:
 *
 * <ol>
 *   <li>Resolve reporting currency via {@link ReportingCurrencyResolver}.</li>
 *   <li>Compute {@code paramsHash} for dedupe; if an in-flight parent already
 *       exists for the same tenant + hash, return that jobId (idempotent).</li>
 *   <li>Insert the parent {@link ReportJob} row with
 *       {@code retention_class = STATUTORY_7Y} (IFRS 17 keys → REGULATORY
 *       family → 7-year statutory retention per I28).</li>
 *   <li>Delegate to {@link Ifrs17ShapingService} for per (portfolio × cohort ×
 *       currency) chunk fan-out.</li>
 *   <li>For each chunk: insert a {@link ReportJobChunk} row, MinIO-upload the
 *       payload if it exceeds the {@link MinIOPayloadStore#SIZE_LIMIT}, and
 *       publish one {@code medfund.report.job-requested} Kafka event with the
 *       {@code ifrs17Json} slot populated.</li>
 *   <li>Emit an {@link AuditEvent} on parent creation (per Rule 8).</li>
 * </ol>
 *
 * <p>Rule-1 guard (currency): all money in the shaping payload is BigDecimal
 * (from {@code Ifrs17ChunkPayload.ifrs17Json()}). Rule-2 guard (tenant): the
 * parent + chunk rows both carry {@code tenantId} from the JWT-sourced value
 * — the aggregator (§18) re-verifies on the completed-event path.
 *
 * <p>Rule-7 (Swagger): the caller controller carries {@code @Operation} +
 * {@code @ApiResponse} annotations so the endpoint documents completely.
 * Rule-8 (audit): {@link #emitAuditEvent} publishes one AuditEvent per parent.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Ifrs17JobService {

    private final ReportJobRepository jobRepository;
    private final ReportJobChunkRepository chunkRepository;
    private final Ifrs17ShapingService shapingService;
    private final ReportJobPublisher publisher;
    private final Optional<MinIOPayloadStore> minioStore;
    private final ObjectMapper objectMapper;
    private final ReportingCurrencyResolver currencyResolver;
    private final AuditPublisher auditPublisher;

    public Mono<Ifrs17JobSubmissionResponse> submit(ReportKey reportKey,
                                                     Ifrs17ReportRequest request,
                                                     UUID tenantId,
                                                     String actorId,
                                                     String actorEmail) {
        return currencyResolver.resolve(tenantId, request.reportingCurrency())
                .flatMap(resolvedCurrency -> submitWithCurrency(reportKey, request, tenantId,
                        actorId, actorEmail, resolvedCurrency));
    }

    private Mono<Ifrs17JobSubmissionResponse> submitWithCurrency(ReportKey reportKey,
                                                                  Ifrs17ReportRequest request,
                                                                  UUID tenantId,
                                                                  String actorId, String actorEmail,
                                                                  String resolvedCurrency) {
        Map<String, Object> params = buildParams(reportKey, request, resolvedCurrency);
        String paramsHash = hash(params);

        return jobRepository
                .findFirstByTenantIdAndParamsHashAndStatusInOrderByRequestedAtDesc(
                        tenantId, paramsHash, List.of("requested", "processing"))
                .flatMap(existing -> chunkRepository.findByParentJobId(existing.getJobId())
                        .count()
                        .map(count -> new Ifrs17JobSubmissionResponse(
                                existing.getJobId(),
                                existing.getStatus(),
                                count.intValue(),
                                true)))
                .switchIfEmpty(insertAndFanOut(reportKey, request, tenantId,
                        actorId, actorEmail, params, paramsHash, resolvedCurrency));
    }

    private Mono<Ifrs17JobSubmissionResponse> insertAndFanOut(ReportKey reportKey,
                                                               Ifrs17ReportRequest request,
                                                               UUID tenantId,
                                                               String actorId, String actorEmail,
                                                               Map<String, Object> params,
                                                               String paramsHash,
                                                               String resolvedCurrency) {
        Ifrs17ReportRequest resolvedRequest = new Ifrs17ReportRequest(
                request.periodStart(), request.periodEnd(),
                request.portfolioIds(), resolvedCurrency);

        return insertParent(reportKey, tenantId, actorId, actorEmail, params, paramsHash)
                .flatMap(parent -> shapingService.shapeChunks(tenantId, resolvedRequest)
                        .concatMap(chunk -> insertAndPublishChunk(parent, tenantId, chunk,
                                actorId, actorEmail))
                        .count()
                        .flatMap(chunkCount -> emitAuditEvent(parent, chunkCount.intValue(),
                                        actorId, actorEmail)
                                .thenReturn(new Ifrs17JobSubmissionResponse(
                                        parent.getJobId(),
                                        parent.getStatus(),
                                        chunkCount.intValue(),
                                        false))));
    }

    private Mono<ReportJob> insertParent(ReportKey reportKey, UUID tenantId,
                                          String actorId, String actorEmail,
                                          Map<String, Object> params, String paramsHash) {
        ReportJob parent = new ReportJob();
        // Leave jobId null so R2DBC does an INSERT (defaults to gen_random_uuid()).
        // Setting the PK up-front makes R2DBC treat the persist as UPDATE and fail
        // "Row with Id [...] does not exist" per bug_r2dbc_pre_populated_id_update_mode.
        parent.setTenantId(tenantId);
        parent.setReportKey(reportKey.name());
        parent.setStatus("requested");
        parent.setParamsJson(jsonOf(params));
        parent.setParamsHash(paramsHash);
        parent.setRequestedAt(OffsetDateTime.now());
        if (actorId != null) {
            try {
                parent.setRequestedBy(UUID.fromString(actorId));
            } catch (IllegalArgumentException ignored) {
                // Non-UUID subject (e.g. "system") — actorEmail carries the human trail.
            }
        }
        parent.setRequestedByEmail(actorEmail);
        parent.setRetentionClass(classifyRetention(reportKey));
        return jobRepository.save(parent);
    }

    private Mono<ReportJobChunk> insertAndPublishChunk(ReportJob parent, UUID tenantId,
                                                       Ifrs17ChunkPayload chunk,
                                                       String actorId, String actorEmail) {
        // Two-phase insert so the payload can reference the DB-generated chunk_id:
        // (1) INSERT with a placeholder params_json (NOT NULL; can't skip). Leave
        //     chunk_id null per bug_r2dbc_pre_populated_id_update_mode → DB fills
        //     via gen_random_uuid().
        // (2) UPDATE with the hydrated payload once chunk_id is known so the
        //     ai-service compute path sees job_id = chunk_id in the ifrs17_json
        //     slot (matches the completed-event envelope's jobId — chunk-lookup
        //     the aggregator §18 does).
        ReportJobChunk row = new ReportJobChunk();
        row.setParentJobId(parent.getJobId());
        row.setPortfolioId(chunk.portfolioId());
        row.setCohortId(chunk.cohortId());
        row.setCurrency(chunk.currency());
        row.setStatus("requested");
        row.setRequestedAt(OffsetDateTime.now());
        row.setParamsJson(jsonOf(Map.of("pending", true)));  // placeholder — replaced in phase (2)

        return chunkRepository.save(row)
                .flatMap(savedChunk -> {
                    Map<String, Object> hydratedJson = new LinkedHashMap<>(chunk.ifrs17Json());
                    hydratedJson.put("job_id", savedChunk.getChunkId().toString());
                    hydratedJson.put("tenant_id", tenantId.toString());
                    byte[] payloadBytes = serializeJsonBytes(hydratedJson);
                    String payloadRef = maybeOffloadToMinio(parent.getJobId(),
                            savedChunk.getChunkId(), payloadBytes);
                    if (payloadRef != null) {
                        savedChunk.setParamsRef(payloadRef);
                        savedChunk.setParamsJson(jsonOf(Map.of("payload_ref", payloadRef)));
                    } else {
                        savedChunk.setParamsJson(jsonOfBytes(payloadBytes));
                    }
                    return chunkRepository.save(savedChunk)
                            .flatMap(persisted -> publishChunk(parent, tenantId, persisted, chunk,
                                            payloadRef != null ? null : hydratedJson,
                                            payloadRef, actorId, actorEmail)
                                    .thenReturn(persisted));
                });
    }

    private Mono<Void> publishChunk(ReportJob parent, UUID tenantId, ReportJobChunk savedChunk,
                                     Ifrs17ChunkPayload chunk, Map<String, Object> inlineJson,
                                     String payloadRef, String actorId, String actorEmail) {
        Map<String, Object> params = Map.of(
                "portfolio_id", chunk.portfolioId().toString(),
                "cohort_id", chunk.cohortId().toString(),
                "currency", chunk.currency(),
                "measurement_model", chunk.measurementModel());
        UUID requestedBy = tryParseUuid(actorId);
        ReportJobRequestedEvent event = new ReportJobRequestedEvent(
                ReportJobRequestedEvent.CURRENT_SCHEMA_VERSION,
                savedChunk.getChunkId(),
                tenantId,
                parent.getJobId(),
                parent.getReportKey(),
                params,
                null,
                null,
                null,
                inlineJson,
                payloadRef,
                requestedBy,
                actorEmail);
        return publisher.publish(event);
    }

    /**
     * Classifies retention per I28: IFRS 17 + REGULATORY family → STATUTORY_7Y
     * (7-year statutory retention); everything else → OPERATIONAL_90D. Applied
     * at parent-insert time — once classified the row keeps its class for the
     * whole 7-year window (no reclassification per plan §NOT DOING).
     */
    static String classifyRetention(ReportKey reportKey) {
        return reportKey.getFamily() == ReportFamily.REGULATORY
                ? ReportJob.RETENTION_STATUTORY_7Y
                : ReportJob.RETENTION_OPERATIONAL_90D;
    }

    /**
     * MinIO fallback per I25: if the payload exceeds
     * {@link MinIOPayloadStore#SIZE_LIMIT}, upload + return the {@code s3://} ref;
     * otherwise inline. When the store bean is absent (e.g. tests that don't
     * enable MinIO), returns null — the caller inlines.
     */
    private String maybeOffloadToMinio(UUID parentJobId, UUID chunkId, byte[] payload) {
        return minioStore.map(store -> store.maybeUploadInput(parentJobId, chunkId, payload))
                .orElse(null);
    }

    private Map<String, Object> buildParams(ReportKey reportKey, Ifrs17ReportRequest request,
                                             String resolvedCurrency) {
        Map<String, Object> params = new TreeMap<>();
        params.put("reportKey", reportKey.name());
        params.put("periodStart", request.periodStart().toString());
        params.put("periodEnd", request.periodEnd().toString());
        params.put("reportingCurrency", resolvedCurrency);
        if (request.portfolioIds() != null && !request.portfolioIds().isEmpty()) {
            List<String> portfolioIds = request.portfolioIds().stream()
                    .map(UUID::toString)
                    .sorted()
                    .toList();
            params.put("portfolioIds", portfolioIds);
        }
        return params;
    }

    private String hash(Map<String, Object> params) {
        try {
            String canonical = objectMapper.writeValueAsString(new TreeMap<>(params));
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Failed to hash IFRS 17 params", e);
        }
    }

    private Json jsonOf(Map<String, Object> value) {
        try {
            return Json.of(objectMapper.writeValueAsString(value));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialise params for job row", e);
        }
    }

    private Json jsonOfBytes(byte[] bytes) {
        return Json.of(new String(bytes, StandardCharsets.UTF_8));
    }

    private byte[] serializeJsonBytes(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsBytes(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialise chunk payload", e);
        }
    }

    private static UUID tryParseUuid(String raw) {
        if (raw == null) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Mono<Void> emitAuditEvent(ReportJob parent, int chunkCount,
                                       String actorId, String actorEmail) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            var event = AuditEvent.create(
                    tenantId != null ? tenantId : parent.getTenantId().toString(),
                    "Ifrs17ReportJob",
                    parent.getJobId().toString(),
                    parent.getReportKey() + " (" + chunkCount + " chunks)",
                    "CREATE",
                    actorId != null ? actorId : AuditActor.SYSTEM_ID,
                    actorEmail != null ? actorEmail : AuditActor.SYSTEM_EMAIL,
                    null,
                    Map.of(
                            "reportKey", parent.getReportKey(),
                            "chunkCount", chunkCount,
                            "retentionClass", parent.getRetentionClass()),
                    new String[]{"reportKey", "chunkCount"},
                    UUID.randomUUID().toString());
            return auditPublisher.publish(event);
        });
    }

    // Test-visible helper: exposed for unit-testing the fan-out arithmetic
    // without wiring the whole Kafka stack. Reactor exposes concatMap through
    // Flux — callers can also drive shaping directly for spec-only tests.
    Flux<Ifrs17ChunkPayload> shapeChunksForTest(UUID tenantId, Ifrs17ReportRequest request) {
        return shapingService.shapeChunks(tenantId, request);
    }

    /**
     * Internal lookup for the XLSX export path (§21). Rule-2 tenant guard is
     * applied here so the caller controller keeps its export code shape
     * identical to the actuarial precedent — 404 (not 403) on cross-tenant
     * so we don't leak "row exists but belongs elsewhere".
     */
    public Mono<ReportJob> get(UUID jobId, UUID tenantId) {
        return jobRepository.findById(jobId)
                .switchIfEmpty(Mono.error(new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND,
                        "IFRS 17 job not found: " + jobId)))
                .flatMap(job -> {
                    if (tenantId != null && !job.getTenantId().equals(tenantId)) {
                        return Mono.error(new org.springframework.web.server.ResponseStatusException(
                                org.springframework.http.HttpStatus.NOT_FOUND,
                                "IFRS 17 job not found: " + jobId));
                    }
                    return Mono.just(job);
                });
    }
}

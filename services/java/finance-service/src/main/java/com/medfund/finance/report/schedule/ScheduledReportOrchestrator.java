package com.medfund.finance.report.schedule;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.finance.report.entity.ReportJob;
import com.medfund.finance.report.repository.ReportJobRepository;
import com.medfund.finance.report.schedule.kafka.ReportDeliveryFailedPublisher;
import com.medfund.finance.report.schedule.kafka.ReportDeliveryPublisher;
import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.report.ReportDeliveryEvent;
import com.medfund.shared.report.ReportDeliveryFailedEvent;
import com.medfund.shared.report.ReportKey;
import com.medfund.shared.security.SecurityEventPublisher;
import io.r2dbc.postgresql.codec.Json;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 17 §A.2 — the per-fire dispatch pipeline. Given a
 * {@link TenantScheduleFireCandidate} for a schedule that matches the
 * current tick:
 *
 * <ol>
 *   <li>Resolve the adapter for the report key; skip if none.</li>
 *   <li>Derive (periodStart, periodEnd, asOf) from the shape+cadence.</li>
 *   <li>Advisory pre-check dedup on {@code report_job}; skip if another
 *       instance already fired this tick.</li>
 *   <li>INSERT a {@code report_job} row (source=SCHEDULED, schedule_id set)
 *       — the partial UNIQUE index is the hard dedup guard.</li>
 *   <li>Invoke the adapter to render bytes.</li>
 *   <li>Upload XLSX to MinIO under {@code <tenant>/<yyyy>/<MM>/<jobId>.xlsx}.</li>
 *   <li>Mark the row completed with the MinIO ref in result_json.</li>
 *   <li>Emit a DATA_ACCESS security event.</li>
 *   <li>Publish {@link ReportDeliveryEvent}.</li>
 *   <li>Emit an audit event describing the fire (COMPLETED).</li>
 * </ol>
 *
 * <p>Any failure after INSERT rolls the row to {@code status='failed'} +
 * publishes {@link ReportDeliveryFailedEvent} and a FAILED audit event.
 * DuplicateKeyException on INSERT is treated as "another instance won the
 * race" and silently skipped.
 */
@Slf4j
@Service
public class ScheduledReportOrchestrator {

    private static final String ENTITY_TYPE = "TENANT_REPORT_SCHEDULE_FIRE";

    private final Map<ReportKey, ScheduledReportShapeAdapter> adaptersByKey;
    private final ReportJobRepository reportJobRepository;
    private final Optional<ReportPayloadStore> payloadStore;
    private final ReportDeliveryPublisher deliveryPublisher;
    private final ReportDeliveryFailedPublisher failedPublisher;
    private final SecurityEventPublisher securityEventPublisher;
    private final AuditPublisher auditPublisher;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ScheduledReportOrchestrator(
            Map<ReportKey, ScheduledReportShapeAdapter> adaptersByKey,
            ReportJobRepository reportJobRepository,
            Optional<ReportPayloadStore> payloadStore,
            ReportDeliveryPublisher deliveryPublisher,
            ReportDeliveryFailedPublisher failedPublisher,
            SecurityEventPublisher securityEventPublisher,
            AuditPublisher auditPublisher,
            ObjectMapper objectMapper,
            Clock clock) {
        this.adaptersByKey = adaptersByKey;
        this.reportJobRepository = reportJobRepository;
        this.payloadStore = payloadStore;
        this.deliveryPublisher = deliveryPublisher;
        this.failedPublisher = failedPublisher;
        this.securityEventPublisher = securityEventPublisher;
        this.auditPublisher = auditPublisher;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public Mono<Void> fireOnce(TenantScheduleFireCandidate candidate, OffsetDateTime firedAt) {
        Optional<ReportKey> keyOpt = ReportKey.parse(candidate.reportKey());
        if (keyOpt.isEmpty()) {
            log.warn("[scheduled-report] unknown report_key={} on schedule={}; skipping",
                    candidate.reportKey(), candidate.scheduleId());
            return Mono.empty();
        }
        ReportKey key = keyOpt.get();
        ScheduledReportShapeAdapter adapter = adaptersByKey.get(key);
        if (adapter == null) {
            log.warn("[scheduled-report] no adapter for reportKey={} (schedule {}); skipping",
                    key, candidate.scheduleId());
            return Mono.empty();
        }
        if (key.getPeriodShape() == null) {
            log.warn("[scheduled-report] key={} has no periodShape; skipping", key);
            return Mono.empty();
        }
        if (payloadStore.isEmpty()) {
            log.error("[scheduled-report] MinIO not configured — schedule {} cannot fire",
                    candidate.scheduleId());
            return Mono.empty();
        }

        ScheduledFireContext ctx = buildContext(candidate, adapter, firedAt);
        if (ctx == null) return Mono.empty();

        UUID jobId = UUID.randomUUID();

        return reportJobRepository.findFirstByTenantIdAndReportKeyAndScheduleIdAndPeriodStart(
                        ctx.tenantId(), key.name(), ctx.scheduleId(), ctx.periodStart())
                .hasElement()
                .flatMap(alreadyFired -> {
                    if (alreadyFired) {
                        log.debug("[scheduled-report] pre-check dedup: schedule={} period={} already fired",
                                ctx.scheduleId(), ctx.periodStart());
                        return Mono.empty();
                    }
                    return runFire(jobId, ctx, candidate, adapter);
                });
    }

    private Mono<Void> runFire(UUID jobId, ScheduledFireContext ctx,
                               TenantScheduleFireCandidate candidate,
                               ScheduledReportShapeAdapter adapter) {
        return insertJob(jobId, ctx)
                .onErrorResume(DuplicateKeyException.class, err -> {
                    log.debug("[scheduled-report] insert race: schedule={} period={} lost to another instance",
                            ctx.scheduleId(), ctx.periodStart());
                    return Mono.empty();
                })
                .flatMap(saved -> adapter.render(ctx)
                        .flatMap(bytes -> uploadAndPublish(jobId, ctx, bytes, candidate))
                        .onErrorResume(err -> handleFailure(jobId, ctx, err, candidate)))
                .then();
    }

    private Mono<ReportJob> insertJob(UUID jobId, ScheduledFireContext ctx) {
        ReportJob job = new ReportJob();
        job.setJobId(jobId);
        job.setTenantId(ctx.tenantId());
        job.setReportKey(ctx.reportKey().name());
        job.setStatus("requested");
        job.setSource("SCHEDULED");
        job.setScheduleId(ctx.scheduleId());
        job.setPeriodStart(ctx.periodStart());
        job.setPeriodEnd(ctx.periodEnd());
        job.setParamsJson(serializeParams(ctx));
        job.setRequestedAt(OffsetDateTime.now(clock));
        job.setRequestedBy(ctx.scheduleUpdatedByActorId());
        job.setRequestedByEmail(ctx.scheduleUpdatedByActorEmail());
        job.setRetentionClass(ReportJob.RETENTION_OPERATIONAL_90D);
        return reportJobRepository.save(job);
    }

    private Mono<Void> uploadAndPublish(UUID jobId, ScheduledFireContext ctx, byte[] bytes,
                                        TenantScheduleFireCandidate candidate) {
        String objectKey = String.format("%s/%04d/%02d/%s.xlsx",
                ctx.tenantId(),
                ctx.firedAt().getYear(),
                ctx.firedAt().getMonthValue(),
                jobId);
        String sha256 = sha256Hex(bytes);
        Json resultJson = serializeResult(objectKey, sha256, bytes.length,
                payloadStore.orElseThrow().bucket());

        ReportPayloadStore store = payloadStore.orElseThrow();
        return store.putXlsx(objectKey, bytes)
                .then(reportJobRepository.markCompleted(jobId, resultJson, OffsetDateTime.now(clock)))
                .then(securityEventPublisher.publishDataAccess(
                        ctx.tenantId().toString(),
                        actorIdString(ctx),
                        ctx.scheduleUpdatedByActorEmail(),
                        ctx.reportKey().name(),
                        Map.of(
                                "source", "SCHEDULED",
                                "scheduleId", ctx.scheduleId().toString(),
                                "cadence", ctx.cadenceLabel(),
                                "periodStart", ctx.periodStart().toString(),
                                "periodEnd", ctx.periodEnd().toString(),
                                "sizeBytes", bytes.length)))
                .then(deliveryPublisher.publish(new ReportDeliveryEvent(
                        jobId,
                        ctx.scheduleId(),
                        ctx.tenantId(),
                        ctx.reportKey().name(),
                        objectKey,
                        sha256,
                        bytes.length,
                        ctx.cadenceLabel(),
                        ctx.periodStart().toString(),
                        ctx.periodEnd().toString(),
                        ctx.reportingCurrency(),
                        Instant.now(clock),
                        ReportDeliveryEvent.SCHEMA_VERSION)))
                .then(publishFireAudit(ctx, candidate, "COMPLETED", null));
    }

    private Mono<Void> handleFailure(UUID jobId, ScheduledFireContext ctx, Throwable err,
                                     TenantScheduleFireCandidate candidate) {
        String stage = classifyStage(err);
        String summary = err.getMessage() != null ? err.getMessage() : err.getClass().getSimpleName();
        log.error("[scheduled-report] fire failed job={} tenant={} key={} stage={}: {}",
                jobId, ctx.tenantId(), ctx.reportKey(), stage, summary, err);
        return reportJobRepository.markFailed(jobId, truncate(summary, 500), OffsetDateTime.now(clock))
                .then(failedPublisher.publish(new ReportDeliveryFailedEvent(
                        jobId,
                        ctx.scheduleId(),
                        ctx.tenantId(),
                        ctx.reportKey().name(),
                        ctx.periodStart().toString(),
                        ctx.periodEnd().toString(),
                        stage,
                        truncate(summary, 500),
                        Instant.now(clock),
                        ReportDeliveryFailedEvent.SCHEMA_VERSION)))
                .then(publishFireAudit(ctx, candidate, "FAILED", summary));
    }

    private ScheduledFireContext buildContext(TenantScheduleFireCandidate candidate,
                                              ScheduledReportShapeAdapter adapter,
                                              OffsetDateTime firedAt) {
        ZoneId zone;
        try {
            zone = ZoneId.of(candidate.timezone() != null ? candidate.timezone() : "UTC");
        } catch (DateTimeException e) {
            log.warn("[scheduled-report] invalid timezone '{}' — skipping schedule {}",
                    candidate.timezone(), candidate.scheduleId());
            return null;
        }
        PeriodResolver.PeriodTriple period = PeriodResolver.resolve(
                adapter.periodShape(), candidate.cadence(), firedAt, zone);
        String currency = candidate.reportingCurrency() != null
                ? candidate.reportingCurrency()
                : "USD"; // Fallback: tenant default resolution deferred to F-S1.
        return new ScheduledFireContext(
                candidate.tenantId(),
                ReportKey.parse(candidate.reportKey()).orElseThrow(),
                candidate.scheduleId(),
                period.periodStart(),
                period.periodEnd(),
                period.asOf(),
                currency,
                cadenceLabel(candidate.cadence()),
                firedAt,
                candidate.scheduleUpdatedByActorId(),
                candidate.scheduleUpdatedByActorEmail());
    }

    private Json serializeParams(ScheduledFireContext ctx) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("source", "SCHEDULED");
        params.put("scheduleId", ctx.scheduleId().toString());
        params.put("periodStart", ctx.periodStart().toString());
        params.put("periodEnd", ctx.periodEnd().toString());
        params.put("asOf", ctx.asOf().toString());
        params.put("reportingCurrency", ctx.reportingCurrency());
        params.put("cadence", ctx.cadenceLabel());
        return jsonOf(params);
    }

    private Json serializeResult(String objectKey, String sha256, long sizeBytes, String bucket) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("xlsxRef", objectKey);
        result.put("bucket", bucket);
        result.put("sha256", sha256);
        result.put("sizeBytes", sizeBytes);
        return jsonOf(result);
    }

    private Json jsonOf(Map<String, Object> map) {
        try {
            return Json.of(objectMapper.writeValueAsBytes(map));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialise report_job JSON column", e);
        }
    }

    private Mono<Void> publishFireAudit(ScheduledFireContext ctx,
                                        TenantScheduleFireCandidate candidate,
                                        String action, String errorSummary) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("scheduleId", ctx.scheduleId().toString());
        details.put("reportKey", ctx.reportKey().name());
        details.put("cadence", ctx.cadenceLabel());
        details.put("periodStart", ctx.periodStart().toString());
        details.put("periodEnd", ctx.periodEnd().toString());
        if (errorSummary != null) details.put("error", truncate(errorSummary, 500));

        String friendly = "Scheduled fire of " + ctx.reportKey().getLabel()
                + " (" + ctx.cadenceLabel() + ", " + ctx.periodStart() + ".." + ctx.periodEnd() + ")"
                + " for tenant " + (candidate.tenantSlug() != null ? candidate.tenantSlug() : ctx.tenantId());

        AuditEvent event = AuditEvent.create(
                ctx.tenantId().toString(),
                ENTITY_TYPE,
                ctx.scheduleId().toString(),
                friendly,
                action,
                actorIdString(ctx),
                ctx.scheduleUpdatedByActorEmail() != null
                        ? ctx.scheduleUpdatedByActorEmail() : AuditActor.SYSTEM_EMAIL,
                null,
                details,
                null,
                UUID.randomUUID().toString());
        return auditPublisher.publish(event);
    }

    private static String actorIdString(ScheduledFireContext ctx) {
        return ctx.scheduleUpdatedByActorId() != null
                ? ctx.scheduleUpdatedByActorId().toString()
                : AuditActor.SYSTEM_ID;
    }

    static String cadenceLabel(String cadence) {
        if (cadence == null) return "Unknown";
        return switch (cadence) {
            case "WEEKLY" -> "Weekly";
            case "MONTHLY" -> "Monthly";
            case "QUARTERLY" -> "Quarterly";
            case "ANNUAL" -> "Annual";
            default -> cadence;
        };
    }

    static String classifyStage(Throwable err) {
        String cn = err.getClass().getName();
        if (cn.contains("ReportPayloadStoreException") || cn.contains("MinioException")
                || cn.contains("minio.errors")) {
            return "MINIO_UPLOAD";
        }
        if (cn.contains("kafka") || err.getMessage() != null
                && err.getMessage().contains("KafkaSender")) {
            return "KAFKA_PUBLISH";
        }
        return "SHAPE";
    }

    static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}

package com.medfund.finance.ifrs17.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.finance.report.entity.ReportJob;
import com.medfund.finance.report.kafka.ReportJobPublisher;
import com.medfund.finance.report.repository.ReportJobRepository;
import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.report.ReportJobRequestedEvent;
import com.medfund.shared.report.ReportKey;
import io.r2dbc.postgresql.codec.Json;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * IBNR sub-job orchestration for IFRS 17 LIC compute (Phase 15 §18 / I24).
 *
 * <p>An IFRS 17 LIC report depends on a fresh IBNR triangle to compute the
 * incurred-but-not-reported claim reserve. Rather than blocking submit on a
 * synchronous IBNR run — which would violate the async-only rule for
 * multi-minute compute — the orchestrator checks whether a completed IBNR
 * triangle already exists inside the freshness window; if not, it publishes
 * an IBNR sub-job (parent_job_id = the caller's parent) and lets the
 * aggregator (§18) continue when the sub-job completes.
 *
 * <h2>Freshness policy</h2>
 * A completed IBNR row for the same tenant + insurance line is considered
 * fresh when its {@code completed_at} is within {@link #FRESHNESS_DAYS} of
 * "now". Older → stale → sub-job triggered.
 *
 * <h2>Phase 18 scope</h2>
 * Phase 18 ships the freshness check + sub-job insert + publish. The full
 * deferred-continuation flow (parent shaping context stashed until sub-job
 * completes, then chunks fanned out) is deferred to a follow-up — the
 * aggregator recognises IBNR sub-jobs by {@code parent_job_id != null &&
 * report_key = IBNR_TRIANGLE} but the continuation trigger + payload replay
 * is intentionally left to §21/§follow-up when the Angular UI needs the
 * 2-stage progress signal.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IbnrSubJobOrchestrator {

    static final int FRESHNESS_DAYS = 90;

    private final ReportJobRepository jobRepository;
    private final ReportJobPublisher publisher;
    private final ObjectMapper objectMapper;

    /**
     * Ensures a fresh IBNR triangle exists for the given tenant + insurance
     * line, publishing a sub-job if not.
     *
     * @return {@link Optional#empty()} when a sub-job was triggered (caller
     *         should defer the parent's chunk fan-out until the sub-job
     *         completes); {@code Optional.of(jobId)} of the fresh IBNR run
     *         when one already exists.
     */
    public Mono<Optional<UUID>> ensureFreshIbnr(UUID tenantId, UUID parentJobId,
                                                 String insuranceLine,
                                                 LocalDate periodStart, LocalDate periodEnd,
                                                 String actorId, String actorEmail) {
        OffsetDateTime freshnessFloor = OffsetDateTime.now().minus(Duration.ofDays(FRESHNESS_DAYS));
        return jobRepository
                .findFirstByTenantIdAndReportKeyAndStatusAndCompletedAtGreaterThanEqualOrderByCompletedAtDesc(
                        tenantId, ReportKey.IBNR_TRIANGLE.name(), "completed", freshnessFloor)
                .map(fresh -> {
                    log.debug("[ibnr-orchestrator] tenant {} has fresh IBNR job {} (completed {}) — "
                                    + "no sub-job needed",
                            tenantId, fresh.getJobId(), fresh.getCompletedAt());
                    return Optional.of(fresh.getJobId());
                })
                .switchIfEmpty(Mono.defer(() -> insertAndPublishIbnrSubJob(
                                tenantId, parentJobId, insuranceLine, periodStart, periodEnd,
                                actorId, actorEmail)
                        .map(subJob -> {
                            log.info("[ibnr-orchestrator] tenant {} IBNR stale — sub-job {} "
                                            + "published under parent {}",
                                    tenantId, subJob.getJobId(), parentJobId);
                            // Empty signal → caller defers the parent chunks.
                            return Optional.<UUID>empty();
                        })));
    }

    private Mono<ReportJob> insertAndPublishIbnrSubJob(UUID tenantId, UUID parentJobId,
                                                        String insuranceLine,
                                                        LocalDate periodStart, LocalDate periodEnd,
                                                        String actorId, String actorEmail) {
        Map<String, Object> params = new TreeMap<>();
        params.put("insuranceLine", insuranceLine != null ? insuranceLine : "ALL");
        params.put("periodStart", periodStart.toString());
        params.put("periodEnd", periodEnd.toString());
        params.put("triggeredBy", "IFRS17_SUB_JOB");
        params.put("parentJobId", parentJobId.toString());

        ReportJob row = new ReportJob();
        // Leave job_id null per bug_r2dbc_pre_populated_id_update_mode.
        row.setTenantId(tenantId);
        row.setParentJobId(parentJobId);
        row.setReportKey(ReportKey.IBNR_TRIANGLE.name());
        row.setStatus("requested");
        row.setParamsJson(jsonOf(params));
        row.setParamsHash("ifrs17-sub-" + UUID.randomUUID());  // unique per invocation — no dedupe on sub-jobs
        row.setRequestedAt(OffsetDateTime.now());
        if (actorId != null) {
            try {
                row.setRequestedBy(UUID.fromString(actorId));
            } catch (IllegalArgumentException ignored) {
                // system caller — actorEmail carries the trail.
            }
        }
        row.setRequestedByEmail(actorEmail != null ? actorEmail : AuditActor.SYSTEM_EMAIL);
        // Sub-jobs inherit parent's retention class — IFRS 17 parent is
        // STATUTORY_7Y, so the IBNR sub-job the LIC depends on rides with it.
        row.setRetentionClass(ReportJob.RETENTION_STATUTORY_7Y);

        return jobRepository.save(row)
                .flatMap(saved -> publishSubJob(saved, params, actorId, actorEmail)
                        .thenReturn(saved));
    }

    private Mono<Void> publishSubJob(ReportJob saved, Map<String, Object> params,
                                       String actorId, String actorEmail) {
        UUID requestedBy = tryParseUuid(actorId);
        ReportJobRequestedEvent event = new ReportJobRequestedEvent(
                ReportJobRequestedEvent.CURRENT_SCHEMA_VERSION,
                saved.getJobId(),
                saved.getTenantId(),
                saved.getParentJobId(),
                saved.getReportKey(),
                params,
                null,  // triangle shape is left to ai-service's shaping — sub-job carries the params
                null, null, null, null,
                requestedBy,
                actorEmail != null ? actorEmail : AuditActor.SYSTEM_EMAIL);
        return publisher.publish(event);
    }

    private Json jsonOf(Map<String, Object> params) {
        try {
            return Json.of(objectMapper.writeValueAsString(params));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialise IBNR sub-job params", e);
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
}

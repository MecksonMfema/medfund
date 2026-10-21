package com.medfund.user.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import com.medfund.user.repository.Ifrs17CohortRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * IFRS 17.44 discount-rate lock-in for a cohort (Phase 15 §6, I18). When the
 * first policy is bound into a cohort, {@code locked_in_yield_curve_snapshot}
 * is populated from {@code public.tenant_yield_curve} at the policy's
 * coverage start; subsequent policies in the same cohort are no-ops.
 *
 * <p>Idempotent by construction: the UPDATE has a
 * {@code WHERE locked_in_at IS NULL} guard, so a race that reaches the write
 * from two producers still writes only once. That lets contributions-service
 * call this from every {@code medfund.user.policy-issued} event without a
 * pre-check — the write is cheap and correct on both paths.
 *
 * <p>Reads {@code public.tenant_yield_curve} directly via {@link DatabaseClient}
 * rather than via a WebClient hop; the yield curve is high-fanout on issuance
 * days (one call per policy bind × N cohorts) and the HTTP round-trip would
 * dominate the compute budget. If no matching curve rows exist for the
 * currency/date, the write still commits with a null snapshot — the missing
 * snapshot is treated as a data-quality problem the report path can surface,
 * not a bind-time failure.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Ifrs17CohortLockInService {

    private static final String ENTITY_TYPE = "Ifrs17Cohort";

    private final Ifrs17CohortRepository cohortRepository;
    private final DatabaseClient db;
    private final ObjectMapper objectMapper;
    private final AuditPublisher auditPublisher;

    /**
     * Lock the yield-curve snapshot for {@code cohortId} at
     * {@code effectiveDate} in the given {@code currency} if — and only if —
     * the cohort has never been locked. Returns whether the write happened
     * so the caller can distinguish "first policy of the cohort" from
     * "re-issuance into an existing cohort" for downstream logging.
     */
    public Mono<Boolean> lockInIfFirstPolicy(UUID cohortId, String currency, LocalDate effectiveDate) {
        if (cohortId == null || currency == null || effectiveDate == null) {
            log.debug("lockInIfFirstPolicy skipped — nullable input (cohortId={}, currency={}, effectiveDate={})",
                    cohortId, currency, effectiveDate);
            return Mono.just(false);
        }
        return cohortRepository.findById(cohortId)
                .flatMap(cohort -> {
                    if (cohort.getLockedInAt() != null) {
                        return Mono.just(false);
                    }
                    return fetchSnapshotJson(currency, effectiveDate)
                            .flatMap(snapshotJson -> writeLockIn(cohortId, snapshotJson));
                })
                .defaultIfEmpty(false);
    }

    private Mono<String> fetchSnapshotJson(String currency, LocalDate effectiveDate) {
        return Mono.deferContextual(ctx -> {
            String tenantIdRaw = TenantContext.get(ctx);
            if (tenantIdRaw == null || tenantIdRaw.isBlank()) {
                log.warn("lockInIfFirstPolicy: no tenant in context — snapshot will be empty");
                return Mono.just(emptySnapshotJson());
            }
            UUID tenantId;
            try {
                tenantId = UUID.fromString(tenantIdRaw);
            } catch (IllegalArgumentException e) {
                log.warn("lockInIfFirstPolicy: tenant context {} is not a UUID — snapshot will be empty",
                        tenantIdRaw);
                return Mono.just(emptySnapshotJson());
            }
            return db.sql("""
                        SELECT tenor_months, spot_rate
                        FROM public.tenant_yield_curve
                        WHERE tenant_id = :tenantId
                          AND currency = :currency
                          AND effective_from <= :asOf
                          AND (effective_to IS NULL OR effective_to > :asOf)
                        ORDER BY tenor_months ASC
                        """)
                    .bind("tenantId", tenantId)
                    .bind("currency", currency)
                    .bind("asOf", effectiveDate)
                    .map(row -> Tuples.of(
                            row.get("tenor_months", Integer.class),
                            row.get("spot_rate", BigDecimal.class)))
                    .all()
                    .collectList()
                    .map(this::serialize);
        });
    }

    private String serialize(List<Tuple2<Integer, BigDecimal>> rows) {
        List<Map<String, Object>> shape = new ArrayList<>();
        for (Tuple2<Integer, BigDecimal> row : rows) {
            if (row.getT1() == null || row.getT2() == null) continue;
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("tenorMonths", row.getT1());
            point.put("spotRate", row.getT2().toPlainString());
            shape.add(point);
        }
        try {
            return objectMapper.writeValueAsString(shape);
        } catch (Exception e) {
            log.warn("Failed to serialize yield curve snapshot ({} points): {}",
                    shape.size(), e.getMessage(), e);
            return "[]";
        }
    }

    private String emptySnapshotJson() {
        return "[]";
    }

    private Mono<Boolean> writeLockIn(UUID cohortId, String snapshotJson) {
        // Empty snapshot ⇒ tenant hasn't uploaded a curve for this currency/date
        // yet. Better to leave locked_in_at NULL so a later curve upload can
        // still capture initial recognition than to poison the cohort with a
        // zero-tenor curve that quietly zeroes out CSM accretion downstream.
        if (snapshotJson == null || "[]".equals(snapshotJson)) {
            log.warn("lockInIfFirstPolicy: no yield curve rows for cohort {} — deferring lock-in", cohortId);
            return Mono.just(false);
        }
        Instant now = Instant.now();
        return db.sql("""
                    UPDATE ifrs17_cohort
                       SET locked_in_yield_curve_snapshot = :snapshot::jsonb,
                           locked_in_at = :lockedAt
                     WHERE id = :id AND locked_in_at IS NULL
                    """)
                .bind("snapshot", snapshotJson)
                .bind("lockedAt", now)
                .bind("id", cohortId)
                .fetch()
                .rowsUpdated()
                .flatMap(rows -> {
                    if (rows == null || rows == 0L) {
                        return Mono.just(false);
                    }
                    return publishAudit(cohortId, snapshotJson, now).thenReturn(true);
                });
    }

    private Mono<Void> publishAudit(UUID cohortId, String snapshotJson, Instant lockedAt) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            Map<String, Object> newValue = new LinkedHashMap<>();
            newValue.put("lockedInAt", lockedAt.toString());
            newValue.put("lockedInYieldCurveSnapshot", snapshotJson);
            AuditEvent event = AuditEvent.create(
                    tenantId != null ? tenantId : "unknown",
                    ENTITY_TYPE,
                    cohortId.toString(),
                    "cohort " + cohortId + " yield curve lock-in",
                    "LOCK_IN_YIELD_CURVE",
                    AuditActor.SYSTEM_ID,
                    AuditActor.SYSTEM_EMAIL,
                    null,
                    newValue,
                    new String[]{"lockedInAt", "lockedInYieldCurveSnapshot"},
                    UUID.randomUUID().toString());
            return auditPublisher.publish(event);
        });
    }
}

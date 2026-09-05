package com.medfund.tenancy.service;

import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.report.ReportCadence;
import com.medfund.shared.report.ReportKey;
import com.medfund.shared.report.ScheduledReportEligibility;
import com.medfund.tenancy.dto.CreateTenantReportScheduleRequest;
import com.medfund.tenancy.dto.TenantReportScheduleResponse;
import com.medfund.tenancy.dto.UpdateTenantReportScheduleRequest;
import com.medfund.tenancy.entity.Tenant;
import com.medfund.tenancy.entity.TenantReportSchedule;
import com.medfund.tenancy.entity.TenantReportScheduleRecipient;
import com.medfund.tenancy.repository.TenantRepository;
import com.medfund.tenancy.repository.TenantReportScheduleRecipientRepository;
import com.medfund.tenancy.repository.TenantReportScheduleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

/**
 * CRUD service for {@code public.tenant_report_schedule} — the Phase 17
 * schedule surface that finance-service {@code ScheduledReportProbe} iterates
 * each hour. Enforces the S1 whitelist so tenants can only pick from the 13
 * operational cadenced keys; regulator/IFRS/AML/FRAUD keys are excluded until
 * the Phase 17.5 follow-up.
 *
 * <p>Cascade-disable (called by {@link TenantReportConfigService} when the
 * enablement toggle flips TRUE → FALSE) flips every matching enabled row to
 * FALSE inside the same transaction and emits one audit event per row.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantReportScheduleService {

    private static final String ENTITY_TYPE = "TENANT_REPORT_SCHEDULE";

    private final TenantReportScheduleRepository repository;
    private final TenantReportScheduleRecipientRepository recipientRepository;
    private final TenantRepository tenantRepository;
    private final R2dbcEntityTemplate r2dbcTemplate;
    private final AuditPublisher auditPublisher;

    public Flux<TenantReportScheduleResponse> list(UUID tenantId) {
        return repository.findByTenantIdOrderByReportKeyAsc(tenantId)
                .concatMap(row -> recipientRepository.findByScheduleIdOrderByEmailAsc(row.getId())
                        .collectList()
                        .map(recipients -> TenantReportScheduleResponse.from(row, recipients)));
    }

    public Mono<TenantReportScheduleResponse> get(UUID tenantId, UUID scheduleId) {
        return repository.findById(scheduleId)
                .filter(row -> row.getTenantId().equals(tenantId))
                .switchIfEmpty(Mono.error(new NoSuchElementException("Schedule not found: " + scheduleId)))
                .flatMap(row -> recipientRepository.findByScheduleIdOrderByEmailAsc(row.getId())
                        .collectList()
                        .map(recipients -> TenantReportScheduleResponse.from(row, recipients)));
    }

    /**
     * Active recipients for a schedule — read path used by notification-service
     * over HTTP. Validates the tenant scope so a cross-tenant scheduleId can't
     * leak recipients.
     */
    public Flux<TenantReportScheduleRecipient> activeRecipients(UUID tenantId, UUID scheduleId) {
        return repository.findById(scheduleId)
                .filter(row -> row.getTenantId().equals(tenantId))
                .switchIfEmpty(Mono.error(new NoSuchElementException("Schedule not found: " + scheduleId)))
                .flatMapMany(row -> recipientRepository.findByScheduleIdAndIsActiveIsTrue(row.getId()));
    }

    @Transactional
    public Mono<TenantReportScheduleResponse> create(UUID tenantId,
                                                     CreateTenantReportScheduleRequest req,
                                                     String actorId,
                                                     String actorEmail) {
        if (actorId == null || actorId.isBlank() || actorEmail == null || actorEmail.isBlank()) {
            return Mono.error(new IllegalArgumentException(
                    "actorId and actorEmail are required — every mutation must be traceable"));
        }
        ReportKey key = ReportKey.parse(req.reportKey())
                .orElseThrow(() -> new IllegalArgumentException("Unknown report key: " + req.reportKey()));
        if (!ScheduledReportEligibility.isEligible(key)) {
            return Mono.error(new IllegalArgumentException(
                    "Report key " + key.name() + " is not eligible for scheduling in v1"));
        }
        validateCadenceShape(req.cadence(), req.dayOfWeek(), req.dayOfMonth());

        UUID actorUuid = parseUuid(actorId);
        TenantReportSchedule row = new TenantReportSchedule();
        row.setTenantId(tenantId);
        row.setReportKey(req.reportKey());
        row.setEnabled(req.enabled());
        row.setCadence(req.cadence().name());
        row.setHourOfDay(req.hourOfDay());
        row.setDayOfWeek(req.dayOfWeek());
        row.setDayOfMonth(req.dayOfMonth());
        row.setReportingCurrency(req.reportingCurrency());
        row.setCreatedByActorId(actorUuid);
        row.setCreatedByActorEmail(actorEmail);
        row.setUpdatedByActorId(actorUuid);
        row.setUpdatedByActorEmail(actorEmail);

        return r2dbcTemplate.insert(row)
                .flatMap(saved -> publishAudit(saved, null, "CREATE", actorId, actorEmail)
                        .thenReturn(saved))
                .map(saved -> TenantReportScheduleResponse.from(saved, List.of()));
    }

    @Transactional
    public Mono<TenantReportScheduleResponse> update(UUID tenantId,
                                                     UUID scheduleId,
                                                     UpdateTenantReportScheduleRequest req,
                                                     String actorId,
                                                     String actorEmail) {
        return repository.findById(scheduleId)
                .switchIfEmpty(Mono.error(new NoSuchElementException("Schedule not found: " + scheduleId)))
                .flatMap(existing -> {
                    if (!existing.getTenantId().equals(tenantId)) {
                        return Mono.<TenantReportSchedule>error(new IllegalArgumentException(
                                "Schedule does not belong to tenant"));
                    }
                    TenantReportSchedule snapshot = copy(existing);
                    if (req.enabled() != null) existing.setEnabled(req.enabled());
                    if (req.cadence() != null) existing.setCadence(req.cadence().name());
                    if (req.hourOfDay() != null) existing.setHourOfDay(req.hourOfDay());
                    if (req.dayOfWeek() != null) existing.setDayOfWeek(req.dayOfWeek());
                    if (req.dayOfMonth() != null) existing.setDayOfMonth(req.dayOfMonth());
                    if (req.reportingCurrency() != null) existing.setReportingCurrency(req.reportingCurrency());
                    // Re-validate cadence shape against the merged view.
                    ReportCadence mergedCadence = ReportCadence.valueOf(existing.getCadence());
                    validateCadenceShape(mergedCadence, existing.getDayOfWeek(), existing.getDayOfMonth());
                    existing.setUpdatedAt(OffsetDateTime.now());
                    existing.setUpdatedByActorId(parseUuid(actorId));
                    existing.setUpdatedByActorEmail(actorEmail);
                    return repository.save(existing)
                            .flatMap(saved -> publishAudit(saved, snapshot, "UPDATE", actorId, actorEmail)
                                    .thenReturn(saved));
                })
                .flatMap(saved -> recipientRepository.findByScheduleIdOrderByEmailAsc(saved.getId())
                        .collectList()
                        .map(recipients -> TenantReportScheduleResponse.from(saved, recipients)));
    }

    @Transactional
    public Mono<Void> delete(UUID tenantId, UUID scheduleId, String actorId, String actorEmail) {
        return repository.findById(scheduleId)
                .switchIfEmpty(Mono.error(new NoSuchElementException("Schedule not found: " + scheduleId)))
                .flatMap(existing -> {
                    if (!existing.getTenantId().equals(tenantId)) {
                        return Mono.<TenantReportSchedule>error(new IllegalArgumentException(
                                "Schedule does not belong to tenant"));
                    }
                    return repository.delete(existing)
                            .then(publishAudit(existing, copy(existing), "DELETE", actorId, actorEmail))
                            .thenReturn(existing);
                })
                .then();
    }

    /**
     * Cascade-disable pass — called from {@link TenantReportConfigService}
     * when a tenant flips a report from enabled TRUE → FALSE. Every matching
     * enabled schedule row flips to FALSE and each emits a CASCADE_DISABLE
     * audit event carrying the previous {@code enabled=true} snapshot.
     * Returns the number of rows affected.
     */
    @Transactional
    public Mono<Integer> cascadeDisable(UUID tenantId, String reportKey,
                                        String actorId, String actorEmail) {
        UUID actorUuid = parseUuid(actorId);
        return repository.findByTenantIdAndReportKeyAndEnabledIsTrue(tenantId, reportKey)
                .collectList()
                .flatMap(affected -> {
                    if (affected.isEmpty()) return Mono.just(0);
                    return repository.cascadeDisable(tenantId, reportKey, actorUuid, actorEmail)
                            .flatMap(count -> Flux.fromIterable(affected)
                                    .concatMap(previous -> {
                                        TenantReportSchedule now = copy(previous);
                                        now.setEnabled(false);
                                        now.setUpdatedAt(OffsetDateTime.now());
                                        now.setUpdatedByActorId(actorUuid);
                                        now.setUpdatedByActorEmail(actorEmail);
                                        return publishAudit(now, previous, "CASCADE_DISABLE",
                                                actorId, actorEmail);
                                    })
                                    .then(Mono.just(count)));
                });
    }

    private static void validateCadenceShape(ReportCadence cadence,
                                             Integer dayOfWeek, Integer dayOfMonth) {
        if (cadence == ReportCadence.WEEKLY && dayOfWeek == null) {
            throw new IllegalArgumentException("dayOfWeek is required for WEEKLY cadence");
        }
        if (cadence == ReportCadence.MONTHLY && dayOfMonth == null) {
            throw new IllegalArgumentException("dayOfMonth is required for MONTHLY cadence");
        }
        if (cadence == ReportCadence.EVENT_DRIVEN) {
            throw new IllegalArgumentException(
                    "EVENT_DRIVEN cadence is regulator-only; not selectable via the schedule form");
        }
    }

    private TenantReportSchedule copy(TenantReportSchedule src) {
        TenantReportSchedule c = new TenantReportSchedule();
        c.setId(src.getId());
        c.setTenantId(src.getTenantId());
        c.setReportKey(src.getReportKey());
        c.setEnabled(src.getEnabled());
        c.setCadence(src.getCadence());
        c.setHourOfDay(src.getHourOfDay());
        c.setDayOfWeek(src.getDayOfWeek());
        c.setDayOfMonth(src.getDayOfMonth());
        c.setReportingCurrency(src.getReportingCurrency());
        c.setLastFiredAt(src.getLastFiredAt());
        c.setLastStatus(src.getLastStatus());
        c.setCreatedAt(src.getCreatedAt());
        c.setCreatedByActorId(src.getCreatedByActorId());
        c.setCreatedByActorEmail(src.getCreatedByActorEmail());
        c.setUpdatedAt(src.getUpdatedAt());
        c.setUpdatedByActorId(src.getUpdatedByActorId());
        c.setUpdatedByActorEmail(src.getUpdatedByActorEmail());
        return c;
    }

    private Mono<Void> publishAudit(TenantReportSchedule current, TenantReportSchedule previous,
                                    String action, String actorId, String actorEmail) {
        Map<String, Object> oldMap = previous != null ? toMap(previous) : null;
        Map<String, Object> newMap = "DELETE".equals(action) ? null : toMap(current);
        String[] changed = ("UPDATE".equals(action) || "CASCADE_DISABLE".equals(action))
                && oldMap != null && newMap != null
                ? changedFields(oldMap, newMap) : null;
        String reportLabel = ReportKey.parse(current.getReportKey())
                .map(ReportKey::getLabel).orElse(current.getReportKey());
        String cadenceLabel = current.getCadence() != null
                ? current.getCadence().charAt(0) + current.getCadence().substring(1).toLowerCase()
                : "unknown";
        return tenantRepository.findById(current.getTenantId())
                .map(Tenant::getSlug)
                .defaultIfEmpty("unknown")
                .flatMap(slug -> auditPublisher.publish(AuditEvent.create(
                        current.getTenantId().toString(),
                        ENTITY_TYPE,
                        current.getId().toString(),
                        String.format("Scheduled %s (%s) for tenant %s",
                                reportLabel, cadenceLabel, slug),
                        action,
                        actorId,
                        actorEmail,
                        oldMap,
                        newMap,
                        changed,
                        UUID.randomUUID().toString())));
    }

    private static Map<String, Object> toMap(TenantReportSchedule row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reportKey", row.getReportKey());
        m.put("enabled", row.getEnabled());
        m.put("cadence", row.getCadence());
        m.put("hourOfDay", row.getHourOfDay());
        m.put("dayOfWeek", row.getDayOfWeek());
        m.put("dayOfMonth", row.getDayOfMonth());
        m.put("reportingCurrency", row.getReportingCurrency());
        return m;
    }

    private static String[] changedFields(Map<String, Object> oldMap, Map<String, Object> newMap) {
        return newMap.keySet().stream()
                .filter(k -> !Objects.equals(oldMap.get(k), newMap.get(k)))
                .toArray(String[]::new);
    }

    private static UUID parseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); }
        catch (IllegalArgumentException e) { return null; }
    }
}

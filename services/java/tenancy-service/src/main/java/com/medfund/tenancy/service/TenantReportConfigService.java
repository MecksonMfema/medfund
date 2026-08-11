package com.medfund.tenancy.service;

import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.report.ReportKey;
import com.medfund.tenancy.dto.TenantReportConfigResponse;
import com.medfund.tenancy.dto.UpdateTenantReportConfigRequest;
import com.medfund.tenancy.dto.UpdateTenantReportConfigRequest.ToggleEntry;
import com.medfund.tenancy.entity.TenantReportConfig;
import com.medfund.tenancy.repository.TenantReportConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Manages the per-tenant report-enablement catalogue (V130).
 *
 * <p>The {@code list} operation returns EVERY known {@link ReportKey} —
 * persisted rows overlay the catalogue-default (enabled) so the tenant-admin
 * grid can render the full set without a separate "known reports" lookup.
 * {@code bulkUpsert} applies a batch of toggles atomically and emits one
 * audit event per row whose value actually changed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantReportConfigService {

    private static final String ENTITY_TYPE = "TENANT_REPORT_CONFIG";

    private final TenantReportConfigRepository repository;
    private final AuditPublisher auditPublisher;

    /**
     * Returns every catalogue key merged with the tenant's persisted overrides.
     * Sorted by family, then label — same order the settings grid renders.
     */
    public Flux<TenantReportConfigResponse> list(UUID tenantId) {
        return repository.findByTenantId(tenantId)
                .collectMap(TenantReportConfig::getReportKey)
                .flatMapMany(byKey -> Flux.fromArray(ReportKey.values())
                        .sort(Comparator
                                .comparing((ReportKey k) -> k.getFamily().ordinal())
                                .thenComparing(ReportKey::getLabel))
                        .map(k -> {
                            TenantReportConfig row = byKey.get(k.name());
                            return row != null
                                    ? TenantReportConfigResponse.from(row)
                                    : TenantReportConfigResponse.defaultFor(tenantId, k);
                        }));
    }

    /**
     * Point lookup for the cross-service enablement check. Returns
     * {@code true} when no row exists — the default-enabled contract.
     */
    public Mono<Boolean> isEnabled(UUID tenantId, String reportKey) {
        return repository.findByTenantIdAndReportKey(tenantId, reportKey)
                .map(row -> Boolean.TRUE.equals(row.getEnabled()))
                .defaultIfEmpty(Boolean.TRUE);
    }

    /**
     * Apply every {@link ToggleEntry} in the request. Unknown report keys
     * are rejected (400) before any writes happen — refusing to persist a
     * catalogue key the platform doesn't understand prevents the tenant
     * admin from typo-ing a key and silently locking a report off.
     */
    @Transactional
    public Flux<TenantReportConfigResponse> bulkUpsert(UUID tenantId,
                                                       UpdateTenantReportConfigRequest req,
                                                       String actorId,
                                                       String actorEmail) {
        List<ToggleEntry> entries = req.entries();
        List<String> unknown = entries.stream()
                .map(ToggleEntry::reportKey)
                .filter(k -> ReportKey.parse(k).isEmpty())
                .toList();
        if (!unknown.isEmpty()) {
            return Flux.error(new IllegalArgumentException(
                    "Unknown report key(s): " + String.join(", ", unknown)));
        }

        return Flux.fromIterable(entries)
                .concatMap(e -> upsertOne(tenantId, e, actorId, actorEmail));
    }

    private Mono<TenantReportConfigResponse> upsertOne(UUID tenantId,
                                                       ToggleEntry entry,
                                                       String actorId,
                                                       String actorEmail) {
        return repository.findByTenantIdAndReportKey(tenantId, entry.reportKey())
                .flatMap(existing -> updateExisting(existing, entry, actorId, actorEmail))
                .switchIfEmpty(insertNew(tenantId, entry, actorId, actorEmail))
                .map(TenantReportConfigResponse::from);
    }

    private Mono<TenantReportConfig> insertNew(UUID tenantId,
                                                ToggleEntry entry,
                                                String actorId,
                                                String actorEmail) {
        TenantReportConfig row = new TenantReportConfig();
        row.setTenantId(tenantId);
        row.setReportKey(entry.reportKey());
        row.setEnabled(entry.enabled());
        row.setUpdatedAt(OffsetDateTime.now());
        row.setUpdatedBy(parseUuid(actorId));
        return repository.save(row)
                .flatMap(saved -> publishAudit(saved, null, "CREATE", actorId, actorEmail)
                        .thenReturn(saved));
    }

    private Mono<TenantReportConfig> updateExisting(TenantReportConfig existing,
                                                    ToggleEntry entry,
                                                    String actorId,
                                                    String actorEmail) {
        boolean wasEnabled = Boolean.TRUE.equals(existing.getEnabled());
        if (wasEnabled == entry.enabled()) {
            // No-op — return without a write or an audit event.
            return Mono.just(existing);
        }
        TenantReportConfig snapshot = copy(existing);
        existing.setEnabled(entry.enabled());
        existing.setUpdatedAt(OffsetDateTime.now());
        existing.setUpdatedBy(parseUuid(actorId));
        return repository.save(existing)
                .flatMap(saved -> publishAudit(saved, snapshot, "UPDATE", actorId, actorEmail)
                        .thenReturn(saved));
    }

    private TenantReportConfig copy(TenantReportConfig src) {
        TenantReportConfig c = new TenantReportConfig();
        c.setId(src.getId());
        c.setTenantId(src.getTenantId());
        c.setReportKey(src.getReportKey());
        c.setEnabled(src.getEnabled());
        c.setUpdatedAt(src.getUpdatedAt());
        c.setUpdatedBy(src.getUpdatedBy());
        return c;
    }

    private Mono<Void> publishAudit(TenantReportConfig current,
                                     TenantReportConfig previous,
                                     String action,
                                     String actorId,
                                     String actorEmail) {
        Map<String, Object> newMap = toMap(current);
        Map<String, Object> oldMap = previous != null ? toMap(previous) : null;
        String[] changed = "UPDATE".equals(action) && oldMap != null
                ? changedFields(oldMap, newMap) : null;

        String entityName = ReportKey.parse(current.getReportKey())
                .map(k -> k.getLabel() + " toggle")
                .orElse(current.getReportKey() + " toggle");

        var event = AuditEvent.create(
                current.getTenantId().toString(),
                ENTITY_TYPE,
                current.getId() != null ? current.getId().toString() : current.getReportKey(),
                entityName,
                action,
                actorId,
                actorEmail,
                oldMap,
                newMap,
                changed,
                UUID.randomUUID().toString());
        return auditPublisher.publish(event);
    }

    private Map<String, Object> toMap(TenantReportConfig c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reportKey", c.getReportKey());
        m.put("enabled", c.getEnabled());
        return m;
    }

    private String[] changedFields(Map<String, Object> oldMap, Map<String, Object> newMap) {
        return newMap.keySet().stream()
                .filter(k -> !Objects.equals(oldMap.get(k), newMap.get(k)))
                .toArray(String[]::new);
    }

    private UUID parseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); }
        catch (IllegalArgumentException e) { return null; }
    }
}

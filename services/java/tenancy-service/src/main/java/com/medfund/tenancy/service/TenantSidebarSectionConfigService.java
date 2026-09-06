package com.medfund.tenancy.service;

import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.sidebar.SidebarSectionKey;
import com.medfund.tenancy.dto.TenantSidebarSectionConfigResponse;
import com.medfund.tenancy.dto.UpdateTenantSidebarSectionConfigRequest;
import com.medfund.tenancy.dto.UpdateTenantSidebarSectionConfigRequest.ToggleEntry;
import com.medfund.tenancy.entity.TenantSidebarSectionConfig;
import com.medfund.tenancy.repository.TenantSidebarSectionConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Manages the per-tenant sidebar-item visibility catalogue (V179).
 *
 * <p>The {@code list} operation returns EVERY known
 * {@link SidebarSectionKey} — persisted rows overlay the
 * catalogue-default (enabled) so the tenant-admin visibility grid
 * can render the full set without a separate "known sections"
 * lookup. {@code bulkUpsert} applies a batch of toggles atomically
 * and emits one audit event per row whose value actually changed
 * (no-op flips do not persist or audit).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantSidebarSectionConfigService {

    private static final String ENTITY_TYPE = "TENANT_SIDEBAR_SECTION_CONFIG";

    private final TenantSidebarSectionConfigRepository repository;
    private final AuditPublisher auditPublisher;

    /**
     * Returns every catalogue key merged with the tenant's persisted
     * overrides. Sorted by group ordinal, then by label so the admin
     * grid renders the sections in the same order the sidebar does.
     */
    public Flux<TenantSidebarSectionConfigResponse> list(UUID tenantId) {
        return repository.findByTenantId(tenantId)
                .collectMap(TenantSidebarSectionConfig::getSectionKey)
                .flatMapMany(byKey -> Flux.fromArray(SidebarSectionKey.values())
                        .sort(Comparator
                                .comparing((SidebarSectionKey k) -> k.getGroup().ordinal())
                                .thenComparing(SidebarSectionKey::getLabel))
                        .map(k -> {
                            TenantSidebarSectionConfig row = byKey.get(k.name());
                            return row != null
                                    ? TenantSidebarSectionConfigResponse.from(row)
                                    : TenantSidebarSectionConfigResponse.defaultFor(tenantId, k);
                        }));
    }

    /**
     * Point lookup for the cross-service enablement check. Returns
     * {@code true} when no row exists — the default-enabled contract.
     */
    public Mono<Boolean> isEnabled(UUID tenantId, String sectionKey) {
        return repository.findByTenantIdAndSectionKey(tenantId, sectionKey)
                .map(row -> Boolean.TRUE.equals(row.getEnabled()))
                .defaultIfEmpty(Boolean.TRUE);
    }

    /**
     * Apply every {@link ToggleEntry} in the request. Unknown
     * section keys are rejected (400) before any writes happen —
     * refusing to persist a catalogue key the platform doesn't
     * understand prevents the tenant admin from typo-ing a key
     * and silently locking a sidebar item off.
     */
    @Transactional
    public Flux<TenantSidebarSectionConfigResponse> bulkUpsert(UUID tenantId,
                                                               UpdateTenantSidebarSectionConfigRequest req,
                                                               String actorId,
                                                               String actorEmail) {
        List<ToggleEntry> entries = req.entries();
        List<String> unknown = entries.stream()
                .map(ToggleEntry::sectionKey)
                .filter(k -> SidebarSectionKey.parse(k).isEmpty())
                .toList();
        if (!unknown.isEmpty()) {
            return Flux.error(new IllegalArgumentException(
                    "Unknown sidebar section key(s): " + String.join(", ", unknown)));
        }

        return Flux.fromIterable(entries)
                .concatMap(e -> upsertOne(tenantId, e, actorId, actorEmail));
    }

    private Mono<TenantSidebarSectionConfigResponse> upsertOne(UUID tenantId,
                                                               ToggleEntry entry,
                                                               String actorId,
                                                               String actorEmail) {
        return repository.findByTenantIdAndSectionKey(tenantId, entry.sectionKey())
                .flatMap(existing -> updateExisting(existing, entry, actorId, actorEmail))
                .switchIfEmpty(insertNew(tenantId, entry, actorId, actorEmail))
                .map(TenantSidebarSectionConfigResponse::from);
    }

    private Mono<TenantSidebarSectionConfig> insertNew(UUID tenantId,
                                                       ToggleEntry entry,
                                                       String actorId,
                                                       String actorEmail) {
        TenantSidebarSectionConfig row = new TenantSidebarSectionConfig();
        row.setTenantId(tenantId);
        row.setSectionKey(entry.sectionKey());
        row.setEnabled(entry.enabled());
        row.setUpdatedAt(OffsetDateTime.now());
        row.setUpdatedBy(parseUuid(actorId));
        return repository.save(row)
                .flatMap(saved -> publishAudit(saved, null, "CREATE", actorId, actorEmail)
                        .thenReturn(saved));
    }

    private Mono<TenantSidebarSectionConfig> updateExisting(TenantSidebarSectionConfig existing,
                                                            ToggleEntry entry,
                                                            String actorId,
                                                            String actorEmail) {
        boolean wasEnabled = Boolean.TRUE.equals(existing.getEnabled());
        if (wasEnabled == entry.enabled()) {
            // No-op — return without a write or an audit event.
            return Mono.just(existing);
        }
        TenantSidebarSectionConfig snapshot = copy(existing);
        existing.setEnabled(entry.enabled());
        existing.setUpdatedAt(OffsetDateTime.now());
        existing.setUpdatedBy(parseUuid(actorId));
        return repository.save(existing)
                .flatMap(saved -> publishAudit(saved, snapshot, "UPDATE", actorId, actorEmail)
                        .thenReturn(saved));
    }

    private TenantSidebarSectionConfig copy(TenantSidebarSectionConfig src) {
        TenantSidebarSectionConfig c = new TenantSidebarSectionConfig();
        c.setId(src.getId());
        c.setTenantId(src.getTenantId());
        c.setSectionKey(src.getSectionKey());
        c.setEnabled(src.getEnabled());
        c.setUpdatedAt(src.getUpdatedAt());
        c.setUpdatedBy(src.getUpdatedBy());
        return c;
    }

    private Mono<Void> publishAudit(TenantSidebarSectionConfig current,
                                    TenantSidebarSectionConfig previous,
                                    String action,
                                    String actorId,
                                    String actorEmail) {
        Map<String, Object> newMap = toMap(current);
        Map<String, Object> oldMap = previous != null ? toMap(previous) : null;
        String[] changed = "UPDATE".equals(action) && oldMap != null
                ? changedFields(oldMap, newMap) : null;

        String entityName = SidebarSectionKey.parse(current.getSectionKey())
                .map(k -> k.getLabel() + " sidebar toggle")
                .orElse(current.getSectionKey() + " sidebar toggle");

        var event = AuditEvent.create(
                current.getTenantId().toString(),
                ENTITY_TYPE,
                current.getId() != null ? current.getId().toString() : current.getSectionKey(),
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

    private Map<String, Object> toMap(TenantSidebarSectionConfig c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sectionKey", c.getSectionKey());
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

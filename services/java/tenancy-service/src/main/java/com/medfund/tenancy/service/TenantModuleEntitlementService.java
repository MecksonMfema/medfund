package com.medfund.tenancy.service;

import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.entitlement.ModuleKey;
import com.medfund.shared.security.SecurityEventPublisher;
import com.medfund.tenancy.dto.ModuleEntitlementResponse;
import com.medfund.tenancy.entity.TenantModuleEntitlement;
import com.medfund.tenancy.kafka.ModuleEntitlementEventPublisher;
import com.medfund.tenancy.repository.TenantModuleEntitlementRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Manages the per-tenant module-entitlement catalogue (V187).
 *
 * <p>A toggle upserts the {@code (tenant, module)} row, emits an audit event
 * (Rule 8), broadcasts a Kafka invalidation so every gated service drops its
 * cached value (producer AFTER the DB commit so a re-read can never observe the
 * pre-toggle value), and — because an entitlement flip is a privilege-level
 * change — emits an {@code ENTITLEMENT_CHANGED} security event (Rule 9 / G5).
 * A no-op flip (same value) neither writes, audits, nor broadcasts.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantModuleEntitlementService {

    private static final String ENTITY_TYPE = "TENANT_MODULE_ENTITLEMENT";

    private final TenantModuleEntitlementRepository repo;
    private final AuditPublisher auditPublisher;
    private final ModuleEntitlementEventPublisher eventPublisher;
    private final SecurityEventPublisher securityEventPublisher;

    public Flux<ModuleEntitlementResponse> list(UUID tenantId) {
        Flux<TenantModuleEntitlement> rows = tenantId != null
                ? repo.findByTenantId(tenantId)
                : repo.findAllOrdered();
        return rows.map(ModuleEntitlementResponse::from);
    }

    public Mono<ModuleEntitlementResponse> setEntitlement(UUID tenantId, String moduleKey, boolean enabled,
                                                          String actorId, String actorEmail) {
        ModuleKey meta = ModuleKey.parse(moduleKey).orElse(null);
        if (meta == null) {
            return Mono.error(new IllegalArgumentException("Unknown module key: " + moduleKey));
        }
        return repo.findByTenantIdAndModuleKey(tenantId, meta.name())
                .flatMap(existing -> updateExisting(existing, meta, enabled, actorId, actorEmail))
                .switchIfEmpty(Mono.defer(() -> insertNew(tenantId, meta, enabled, actorId, actorEmail)));
    }

    private Mono<ModuleEntitlementResponse> insertNew(UUID tenantId, ModuleKey meta, boolean enabled,
                                                      String actorId, String actorEmail) {
        TenantModuleEntitlement row = new TenantModuleEntitlement();
        row.setTenantId(tenantId);
        row.setModuleKey(meta.name());
        row.setEnabled(enabled);
        row.setUpdatedAt(OffsetDateTime.now());
        row.setUpdatedBy(parseUuid(actorId));
        row.setVersion(0L);
        return repo.save(row)
                .flatMap(saved -> afterCommit(saved, meta, false, enabled, actorId, actorEmail));
    }

    private Mono<ModuleEntitlementResponse> updateExisting(TenantModuleEntitlement existing, ModuleKey meta,
                                                           boolean enabled, String actorId, String actorEmail) {
        boolean before = Boolean.TRUE.equals(existing.getEnabled());
        if (before == enabled) {
            // No-op — return the current state without a write, audit, or broadcast.
            return Mono.just(ModuleEntitlementResponse.from(existing));
        }
        existing.setEnabled(enabled);
        existing.setUpdatedAt(OffsetDateTime.now());
        existing.setUpdatedBy(parseUuid(actorId));
        existing.setVersion((existing.getVersion() != null ? existing.getVersion() : 0L) + 1L);
        return repo.save(existing)
                .flatMap(saved -> afterCommit(saved, meta, before, enabled, actorId, actorEmail));
    }

    /**
     * Audit → broadcast (Kafka invalidation) → security event, in that order.
     * The broadcast runs after the row is committed so a consumer re-reading on
     * invalidation can never observe the pre-toggle value; the security event is
     * fire-and-forget and never blocks the response.
     *
     * <p>All three are best-effort: {@link AuditPublisher#publish} propagates a
     * broker error (unlike the broadcast + security publishers, which swallow),
     * so without this {@code onErrorResume} an audit-topic hiccup would
     * short-circuit the {@code .then()} chain and the {@code platform.module-
     * entitlements.v1} broadcast would never fire — leaving the Go gateway (no
     * TTL/resync) 403-ing an already-enabled tenant until restart, with the
     * admin's retry hitting the no-op path. The row is already committed, so the
     * broadcast must not be hostage to audit-publish success; the failure is
     * logged here and in {@code AuditPublisher}.
     */
    private Mono<ModuleEntitlementResponse> afterCommit(TenantModuleEntitlement saved, ModuleKey meta,
                                                        boolean before, boolean after,
                                                        String actorId, String actorEmail) {
        return emitAudit(saved, meta, before, after, actorId, actorEmail)
                .onErrorResume(e -> {
                    log.error("Audit publish failed for entitlement toggle tenant={} module={}; "
                            + "continuing with broadcast + security event", saved.getTenantId(), meta.name(), e);
                    return Mono.empty();
                })
                .then(eventPublisher.publish(saved.getTenantId().toString(), meta.name(), after, actorEmail))
                .then(securityEventPublisher.publishEntitlementChanged(
                        saved.getTenantId().toString(), actorId, actorEmail, meta.name(), after))
                .thenReturn(ModuleEntitlementResponse.from(saved));
    }

    private Mono<Void> emitAudit(TenantModuleEntitlement row, ModuleKey meta, boolean before, boolean after,
                                 String actorId, String actorEmail) {
        Map<String, Object> oldMap = new LinkedHashMap<>();
        oldMap.put("enabled", before);
        Map<String, Object> newMap = new LinkedHashMap<>();
        newMap.put("enabled", after);
        AuditEvent event = AuditEvent.create(
                row.getTenantId().toString(),
                ENTITY_TYPE,
                row.getId() != null ? row.getId().toString() : meta.name(),
                meta.getDisplayName() + " entitlement",
                "UPDATE",
                actorId,
                actorEmail,
                oldMap,
                newMap,
                new String[]{"enabled"},
                UUID.randomUUID().toString()
        );
        return auditPublisher.publish(event);
    }

    private UUID parseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); }
        catch (IllegalArgumentException e) { return null; }
    }
}

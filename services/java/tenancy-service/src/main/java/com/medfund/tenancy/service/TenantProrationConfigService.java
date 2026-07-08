package com.medfund.tenancy.service;

import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.tenancy.dto.TenantProrationConfigResponse;
import com.medfund.tenancy.dto.UpdateTenantProrationConfigRequest;
import com.medfund.tenancy.entity.TenantProrationConfig;
import com.medfund.tenancy.repository.TenantProrationConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Manages per-tenant benefit-limit proration configuration. Read is
 * side-effect free and returns the platform default (NONE) when no row exists.
 * Write upserts the row and emits an audit event summarising which fields
 * changed (per the AuditActor / AuditEvent pattern in coding-standards.md).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantProrationConfigService {

    private static final String ENTITY_TYPE = "TENANT_PRORATION_CONFIG";
    private static final String ENTITY_NAME = "Proration Config";

    private final TenantProrationConfigRepository repository;
    private final R2dbcEntityTemplate r2dbcTemplate;
    private final AuditPublisher auditPublisher;

    public Mono<TenantProrationConfigResponse> get(UUID tenantId) {
        return repository.findById(tenantId)
                .map(TenantProrationConfigResponse::from)
                .defaultIfEmpty(TenantProrationConfigResponse.platformDefault(tenantId));
    }

    @Transactional
    public Mono<TenantProrationConfigResponse> upsert(UUID tenantId,
                                                     UpdateTenantProrationConfigRequest req,
                                                     String actorId,
                                                     String actorEmail) {
        return repository.findById(tenantId)
                .flatMap(existing -> updateExisting(existing, req, actorId, actorEmail))
                .switchIfEmpty(insertNew(tenantId, req, actorId, actorEmail))
                .map(TenantProrationConfigResponse::from);
    }

    private Mono<TenantProrationConfig> insertNew(UUID tenantId,
                                                  UpdateTenantProrationConfigRequest req,
                                                  String actorId,
                                                  String actorEmail) {
        TenantProrationConfig fresh = new TenantProrationConfig();
        fresh.setTenantId(tenantId);
        applyRequest(fresh, req);
        fresh.setUpdatedAt(OffsetDateTime.now());
        fresh.setUpdatedBy(parseUuid(actorId));
        // R2dbcEntityTemplate.insert honours the @Id as the tenant-id PK.
        return r2dbcTemplate.insert(fresh)
                .flatMap(saved -> publishAudit(saved, null, "CREATE", actorId, actorEmail)
                        .thenReturn(saved));
    }

    private Mono<TenantProrationConfig> updateExisting(TenantProrationConfig existing,
                                                      UpdateTenantProrationConfigRequest req,
                                                      String actorId,
                                                      String actorEmail) {
        TenantProrationConfig snapshot = copy(existing);
        applyRequest(existing, req);
        existing.setUpdatedAt(OffsetDateTime.now());
        existing.setUpdatedBy(parseUuid(actorId));
        return repository.save(existing)
                .flatMap(saved -> publishAudit(saved, snapshot, "UPDATE", actorId, actorEmail)
                        .thenReturn(saved));
    }

    private void applyRequest(TenantProrationConfig c, UpdateTenantProrationConfigRequest req) {
        c.setDefaultStrategy(req.defaultStrategy());
        c.setUpgradeStrategy(req.upgradeStrategy());
        c.setDowngradeStrategy(req.downgradeStrategy());
        c.setCurrencyStrategy(req.currencyStrategy());
        c.setIncrementWaitDays(req.incrementWaitDays());
    }

    private TenantProrationConfig copy(TenantProrationConfig src) {
        TenantProrationConfig c = new TenantProrationConfig();
        c.setTenantId(src.getTenantId());
        c.setDefaultStrategy(src.getDefaultStrategy());
        c.setUpgradeStrategy(src.getUpgradeStrategy());
        c.setDowngradeStrategy(src.getDowngradeStrategy());
        c.setCurrencyStrategy(src.getCurrencyStrategy());
        c.setIncrementWaitDays(src.getIncrementWaitDays());
        c.setUpdatedAt(src.getUpdatedAt());
        c.setUpdatedBy(src.getUpdatedBy());
        return c;
    }

    private Mono<Void> publishAudit(TenantProrationConfig current,
                                    TenantProrationConfig previous,
                                    String action,
                                    String actorId,
                                    String actorEmail) {
        Map<String, Object> oldMap = previous != null ? toMap(previous) : null;
        Map<String, Object> newMap = toMap(current);
        String[] changed = "UPDATE".equals(action) && oldMap != null
                ? changedFields(oldMap, newMap) : null;

        var event = AuditEvent.create(
                current.getTenantId().toString(),
                ENTITY_TYPE,
                current.getTenantId().toString(),
                ENTITY_NAME,
                action,
                actorId,
                actorEmail,
                oldMap,
                newMap,
                changed,
                UUID.randomUUID().toString()
        );
        return auditPublisher.publish(event);
    }

    private Map<String, Object> toMap(TenantProrationConfig c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("defaultStrategy", c.getDefaultStrategy());
        m.put("upgradeStrategy", c.getUpgradeStrategy());
        m.put("downgradeStrategy", c.getDowngradeStrategy());
        m.put("currencyStrategy", c.getCurrencyStrategy());
        m.put("incrementWaitDays", c.getIncrementWaitDays());
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

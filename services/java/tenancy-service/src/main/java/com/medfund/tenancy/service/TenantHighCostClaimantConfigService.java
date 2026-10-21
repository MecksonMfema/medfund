package com.medfund.tenancy.service;

import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.tenancy.dto.TenantHighCostClaimantConfigResponse;
import com.medfund.tenancy.dto.UpdateTenantHighCostClaimantConfigRequest;
import com.medfund.tenancy.entity.Tenant;
import com.medfund.tenancy.entity.TenantHighCostClaimantConfig;
import com.medfund.tenancy.repository.TenantHighCostClaimantConfigRepository;
import com.medfund.tenancy.repository.TenantRepository;
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
 * Manages per-tenant high-cost-claimant threshold config (V132). Read is
 * side-effect free and reports "unconfigured" (nulls) when no row exists —
 * the HIGH_COST_CLAIMANT report then renders empty with a warning rather
 * than a fabricated default (G46). Write upserts the row and emits an audit
 * event naming the tenant slug (per {@code feedback_audit_entity_name} —
 * entityName is friendly text, never the UUID).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantHighCostClaimantConfigService {

    private static final String ENTITY_TYPE = "TENANT_HIGH_COST_CLAIMANT_CONFIG";

    private final TenantHighCostClaimantConfigRepository repository;
    private final TenantRepository tenantRepository;
    private final R2dbcEntityTemplate r2dbcTemplate;
    private final AuditPublisher auditPublisher;

    public Mono<TenantHighCostClaimantConfigResponse> get(UUID tenantId) {
        return repository.findByTenantId(tenantId)
                .map(TenantHighCostClaimantConfigResponse::from)
                .defaultIfEmpty(TenantHighCostClaimantConfigResponse.unconfigured(tenantId));
    }

    @Transactional
    public Mono<TenantHighCostClaimantConfigResponse> upsert(UUID tenantId,
                                                             UpdateTenantHighCostClaimantConfigRequest req,
                                                             String actorId,
                                                             String actorEmail) {
        return repository.findByTenantId(tenantId)
                .flatMap(existing -> updateExisting(existing, req, actorId, actorEmail))
                .switchIfEmpty(Mono.defer(() -> insertNew(tenantId, req, actorId, actorEmail)))
                .map(TenantHighCostClaimantConfigResponse::from);
    }

    private Mono<TenantHighCostClaimantConfig> insertNew(UUID tenantId,
                                                         UpdateTenantHighCostClaimantConfigRequest req,
                                                         String actorId,
                                                         String actorEmail) {
        TenantHighCostClaimantConfig fresh = new TenantHighCostClaimantConfig();
        fresh.setTenantId(tenantId);
        applyRequest(fresh, req);
        fresh.setUpdatedAt(OffsetDateTime.now());
        fresh.setUpdatedBy(parseUuid(actorId));
        return r2dbcTemplate.insert(fresh)
                .flatMap(saved -> publishAudit(saved, null, "CREATE", actorId, actorEmail)
                        .thenReturn(saved));
    }

    private Mono<TenantHighCostClaimantConfig> updateExisting(TenantHighCostClaimantConfig existing,
                                                              UpdateTenantHighCostClaimantConfigRequest req,
                                                              String actorId,
                                                              String actorEmail) {
        TenantHighCostClaimantConfig snapshot = copy(existing);
        applyRequest(existing, req);
        existing.setUpdatedAt(OffsetDateTime.now());
        existing.setUpdatedBy(parseUuid(actorId));
        return repository.save(existing)
                .flatMap(saved -> publishAudit(saved, snapshot, "UPDATE", actorId, actorEmail)
                        .thenReturn(saved));
    }

    private void applyRequest(TenantHighCostClaimantConfig c, UpdateTenantHighCostClaimantConfigRequest req) {
        c.setThresholdAmount(req.thresholdAmount());
        c.setCurrencyCode(req.currencyCode());
    }

    private TenantHighCostClaimantConfig copy(TenantHighCostClaimantConfig src) {
        TenantHighCostClaimantConfig c = new TenantHighCostClaimantConfig();
        c.setId(src.getId());
        c.setTenantId(src.getTenantId());
        c.setThresholdAmount(src.getThresholdAmount());
        c.setCurrencyCode(src.getCurrencyCode());
        c.setUpdatedAt(src.getUpdatedAt());
        c.setUpdatedBy(src.getUpdatedBy());
        return c;
    }

    private Mono<Void> publishAudit(TenantHighCostClaimantConfig current,
                                    TenantHighCostClaimantConfig previous,
                                    String action,
                                    String actorId,
                                    String actorEmail) {
        Map<String, Object> oldMap = previous != null ? toMap(previous) : null;
        Map<String, Object> newMap = toMap(current);
        String[] changed = "UPDATE".equals(action) && oldMap != null
                ? changedFields(oldMap, newMap) : null;

        return tenantRepository.findById(current.getTenantId())
                .map(Tenant::getSlug)
                .defaultIfEmpty("unknown")
                .flatMap(slug -> auditPublisher.publish(AuditEvent.create(
                        current.getTenantId().toString(),
                        ENTITY_TYPE,
                        current.getTenantId().toString(),
                        "HighCostClaimantConfig for tenant " + slug,
                        action,
                        actorId,
                        actorEmail,
                        oldMap,
                        newMap,
                        changed,
                        UUID.randomUUID().toString())));
    }

    private Map<String, Object> toMap(TenantHighCostClaimantConfig c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("thresholdAmount", c.getThresholdAmount());
        m.put("currencyCode", c.getCurrencyCode());
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

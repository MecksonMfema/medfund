package com.medfund.tenancy.service;

import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.tenancy.dto.TenantCtcAutoConfigResponse;
import com.medfund.tenancy.dto.UpdateTenantCtcAutoConfigRequest;
import com.medfund.tenancy.entity.TenantCtcAutoConfig;
import com.medfund.tenancy.repository.TenantCtcAutoConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Manages per-tenant auto-CTC configuration (V129). Read is side-effect
 * free and returns the platform default (disabled) when no row exists.
 * Write upserts the row and emits an audit event summarising which
 * fields changed.
 *
 * <p>Structural twin of {@code TenantProrationConfigService} — kept
 * separate rather than abstracted so each config's audit shape and
 * validation stays inline and greppable.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantCtcAutoConfigService {

    private static final String ENTITY_TYPE = "TENANT_CTC_AUTO_CONFIG";
    private static final String ENTITY_NAME = "CTC Auto Config";

    private final TenantCtcAutoConfigRepository repository;
    private final R2dbcEntityTemplate r2dbcTemplate;
    private final AuditPublisher auditPublisher;

    public Mono<TenantCtcAutoConfigResponse> get(UUID tenantId) {
        return repository.findById(tenantId)
                .map(TenantCtcAutoConfigResponse::from)
                .defaultIfEmpty(TenantCtcAutoConfigResponse.platformDefault(tenantId));
    }

    @Transactional
    public Mono<TenantCtcAutoConfigResponse> upsert(UUID tenantId,
                                                    UpdateTenantCtcAutoConfigRequest req,
                                                    String actorId,
                                                    String actorEmail) {
        return repository.findById(tenantId)
                .flatMap(existing -> updateExisting(existing, req, actorId, actorEmail))
                .switchIfEmpty(insertNew(tenantId, req, actorId, actorEmail))
                .map(TenantCtcAutoConfigResponse::from);
    }

    private Mono<TenantCtcAutoConfig> insertNew(UUID tenantId,
                                                UpdateTenantCtcAutoConfigRequest req,
                                                String actorId,
                                                String actorEmail) {
        TenantCtcAutoConfig fresh = new TenantCtcAutoConfig();
        fresh.setTenantId(tenantId);
        applyRequest(fresh, req);
        fresh.setUpdatedAt(OffsetDateTime.now());
        fresh.setUpdatedBy(parseUuid(actorId));
        return r2dbcTemplate.insert(fresh)
                .flatMap(saved -> publishAudit(saved, null, "CREATE", actorId, actorEmail)
                        .thenReturn(saved));
    }

    private Mono<TenantCtcAutoConfig> updateExisting(TenantCtcAutoConfig existing,
                                                     UpdateTenantCtcAutoConfigRequest req,
                                                     String actorId,
                                                     String actorEmail) {
        TenantCtcAutoConfig snapshot = copy(existing);
        applyRequest(existing, req);
        existing.setUpdatedAt(OffsetDateTime.now());
        existing.setUpdatedBy(parseUuid(actorId));
        return repository.save(existing)
                .flatMap(saved -> publishAudit(saved, snapshot, "UPDATE", actorId, actorEmail)
                        .thenReturn(saved));
    }

    private void applyRequest(TenantCtcAutoConfig c, UpdateTenantCtcAutoConfigRequest req) {
        c.setEnabled(req.enabled());
        c.setMinMemberBalanceThreshold(req.minMemberBalanceThreshold() != null
                ? req.minMemberBalanceThreshold() : BigDecimal.ZERO);
        c.setMaxPerCtcAmount(req.maxPerCtcAmount());
        c.setThresholdCurrency(req.thresholdCurrency());
    }

    private TenantCtcAutoConfig copy(TenantCtcAutoConfig src) {
        TenantCtcAutoConfig c = new TenantCtcAutoConfig();
        c.setTenantId(src.getTenantId());
        c.setEnabled(src.getEnabled());
        c.setMinMemberBalanceThreshold(src.getMinMemberBalanceThreshold());
        c.setMaxPerCtcAmount(src.getMaxPerCtcAmount());
        c.setThresholdCurrency(src.getThresholdCurrency());
        c.setUpdatedAt(src.getUpdatedAt());
        c.setUpdatedBy(src.getUpdatedBy());
        return c;
    }

    private Mono<Void> publishAudit(TenantCtcAutoConfig current,
                                    TenantCtcAutoConfig previous,
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

    private Map<String, Object> toMap(TenantCtcAutoConfig c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", c.getEnabled());
        m.put("minMemberBalanceThreshold", c.getMinMemberBalanceThreshold());
        m.put("maxPerCtcAmount", c.getMaxPerCtcAmount());
        m.put("thresholdCurrency", c.getThresholdCurrency());
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

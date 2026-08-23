package com.medfund.tenancy.service;

import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.tenancy.dto.TenantAutoLapseConfigResponse;
import com.medfund.tenancy.dto.UpdateTenantAutoLapseConfigRequest;
import com.medfund.tenancy.entity.Tenant;
import com.medfund.tenancy.entity.TenantAutoLapseConfig;
import com.medfund.tenancy.repository.TenantAutoLapseConfigRepository;
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
 * Manages the tenant's auto-lapse configuration (V133). Read reports
 * "unconfigured" (enabled=false + null threshold/grace) when no row
 * exists. Write upserts the row and emits an audit event naming the
 * tenant slug (per {@code feedback_audit_entity_name}).
 *
 * <p>Downstream consumers of the auto-lapse chain — the arrears
 * escalation executor in contributions-service and the arrears
 * consumers in user-service — read this via a public-schema lookup
 * (contributions) or a cross-service WebClient (user-service). Both
 * treat missing config as disabled.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantAutoLapseConfigService {

    private static final String ENTITY_TYPE = "TENANT_AUTO_LAPSE_CONFIG";

    private final TenantAutoLapseConfigRepository repository;
    private final TenantRepository tenantRepository;
    private final R2dbcEntityTemplate r2dbcTemplate;
    private final AuditPublisher auditPublisher;

    public Mono<TenantAutoLapseConfigResponse> get(UUID tenantId) {
        return repository.findByTenantId(tenantId)
                .map(TenantAutoLapseConfigResponse::from)
                .defaultIfEmpty(TenantAutoLapseConfigResponse.unconfigured(tenantId));
    }

    @Transactional
    public Mono<TenantAutoLapseConfigResponse> upsert(UUID tenantId,
                                                      UpdateTenantAutoLapseConfigRequest req,
                                                      String actorId,
                                                      String actorEmail) {
        validate(req);
        return repository.findByTenantId(tenantId)
                .flatMap(existing -> updateExisting(existing, req, actorId, actorEmail))
                .switchIfEmpty(Mono.defer(() -> insertNew(tenantId, req, actorId, actorEmail)))
                .map(TenantAutoLapseConfigResponse::from);
    }

    private void validate(UpdateTenantAutoLapseConfigRequest req) {
        // Belt-and-braces alongside the bean-validation annotations —
        // when enabled=true both threshold and grace must be present so
        // downstream consumers have a usable window. When enabled=false
        // both fields are optional (the sweep skips the tenant either way).
        if (Boolean.TRUE.equals(req.enabled())) {
            if (req.arrearsThresholdMonths() == null || req.graceWindowDays() == null) {
                throw new IllegalArgumentException(
                        "arrearsThresholdMonths and graceWindowDays are required when enabled=true");
            }
        }
    }

    private Mono<TenantAutoLapseConfig> insertNew(UUID tenantId,
                                                  UpdateTenantAutoLapseConfigRequest req,
                                                  String actorId,
                                                  String actorEmail) {
        TenantAutoLapseConfig fresh = new TenantAutoLapseConfig();
        fresh.setTenantId(tenantId);
        applyRequest(fresh, req);
        fresh.setCreatedAt(OffsetDateTime.now());
        fresh.setUpdatedAt(OffsetDateTime.now());
        fresh.setActorId(parseUuid(actorId));
        fresh.setActorEmail(actorEmail);
        return r2dbcTemplate.insert(fresh)
                .flatMap(saved -> publishAudit(saved, null, "CREATE", actorId, actorEmail)
                        .thenReturn(saved));
    }

    private Mono<TenantAutoLapseConfig> updateExisting(TenantAutoLapseConfig existing,
                                                       UpdateTenantAutoLapseConfigRequest req,
                                                       String actorId,
                                                       String actorEmail) {
        TenantAutoLapseConfig snapshot = copy(existing);
        applyRequest(existing, req);
        existing.setUpdatedAt(OffsetDateTime.now());
        existing.setActorId(parseUuid(actorId));
        existing.setActorEmail(actorEmail);
        return repository.save(existing)
                .flatMap(saved -> publishAudit(saved, snapshot, "UPDATE", actorId, actorEmail)
                        .thenReturn(saved));
    }

    private void applyRequest(TenantAutoLapseConfig c, UpdateTenantAutoLapseConfigRequest req) {
        c.setEnabled(Boolean.TRUE.equals(req.enabled()));
        c.setArrearsThresholdMonths(req.arrearsThresholdMonths());
        c.setGraceWindowDays(req.graceWindowDays());
    }

    private TenantAutoLapseConfig copy(TenantAutoLapseConfig src) {
        TenantAutoLapseConfig c = new TenantAutoLapseConfig();
        c.setId(src.getId());
        c.setTenantId(src.getTenantId());
        c.setEnabled(src.isEnabled());
        c.setArrearsThresholdMonths(src.getArrearsThresholdMonths());
        c.setGraceWindowDays(src.getGraceWindowDays());
        c.setCreatedAt(src.getCreatedAt());
        c.setUpdatedAt(src.getUpdatedAt());
        c.setActorId(src.getActorId());
        c.setActorEmail(src.getActorEmail());
        return c;
    }

    private Mono<Void> publishAudit(TenantAutoLapseConfig current,
                                    TenantAutoLapseConfig previous,
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
                        "AutoLapseConfig for tenant " + slug,
                        action,
                        actorId,
                        actorEmail,
                        oldMap,
                        newMap,
                        changed,
                        UUID.randomUUID().toString())));
    }

    private Map<String, Object> toMap(TenantAutoLapseConfig c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", c.isEnabled());
        m.put("arrearsThresholdMonths", c.getArrearsThresholdMonths());
        m.put("graceWindowDays", c.getGraceWindowDays());
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

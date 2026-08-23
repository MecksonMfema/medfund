package com.medfund.tenancy.service;

import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.tenancy.dto.TenantEndorsementConfigResponse;
import com.medfund.tenancy.dto.UpdateTenantEndorsementConfigRequest;
import com.medfund.tenancy.entity.Tenant;
import com.medfund.tenancy.entity.TenantEndorsementConfig;
import com.medfund.tenancy.repository.TenantEndorsementConfigRepository;
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
 * Manages the tenant's endorsement four-eyes configuration (V134). Read
 * reports "unconfigured" (enabled=false + nulls) when no row exists.
 * Write upserts the row and emits an audit event naming the tenant slug
 * (per {@code feedback_audit_entity_name}).
 *
 * <p>Consumed by user-service {@code PolicyEndorsementService.createDraft}
 * via {@code TenantEndorsementConfigClient} to decide whether a new
 * endorsement should auto-commit or drop into DRAFT for supervisor review.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantEndorsementConfigService {

    private static final String ENTITY_TYPE = "TENANT_ENDORSEMENT_CONFIG";

    private final TenantEndorsementConfigRepository repository;
    private final TenantRepository tenantRepository;
    private final R2dbcEntityTemplate r2dbcTemplate;
    private final AuditPublisher auditPublisher;

    public Mono<TenantEndorsementConfigResponse> get(UUID tenantId) {
        return repository.findByTenantId(tenantId)
                .map(TenantEndorsementConfigResponse::from)
                .defaultIfEmpty(TenantEndorsementConfigResponse.unconfigured(tenantId));
    }

    @Transactional
    public Mono<TenantEndorsementConfigResponse> upsert(UUID tenantId,
                                                        UpdateTenantEndorsementConfigRequest req,
                                                        String actorId,
                                                        String actorEmail) {
        validate(req);
        return repository.findByTenantId(tenantId)
                .flatMap(existing -> updateExisting(existing, req, actorId, actorEmail))
                .switchIfEmpty(Mono.defer(() -> insertNew(tenantId, req, actorId, actorEmail)))
                .map(TenantEndorsementConfigResponse::from);
    }

    private void validate(UpdateTenantEndorsementConfigRequest req) {
        // Belt-and-braces alongside the bean-validation annotations —
        // when enabled=true, the threshold amount + currency must both be
        // present so the gate has a comparison basis. When enabled=false
        // both may be null (the gate short-circuits either way).
        if (Boolean.TRUE.equals(req.enabled())) {
            if (req.fourEyesThresholdAmount() == null || req.thresholdCurrency() == null) {
                throw new IllegalArgumentException(
                        "fourEyesThresholdAmount and thresholdCurrency are required when enabled=true");
            }
        }
        // Paired-nullability: matches V134's chk_endorsement_threshold_paired.
        if ((req.fourEyesThresholdAmount() == null) != (req.thresholdCurrency() == null)) {
            throw new IllegalArgumentException(
                    "fourEyesThresholdAmount + thresholdCurrency must be provided together (or both omitted)");
        }
    }

    private Mono<TenantEndorsementConfig> insertNew(UUID tenantId,
                                                    UpdateTenantEndorsementConfigRequest req,
                                                    String actorId,
                                                    String actorEmail) {
        TenantEndorsementConfig fresh = new TenantEndorsementConfig();
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

    private Mono<TenantEndorsementConfig> updateExisting(TenantEndorsementConfig existing,
                                                         UpdateTenantEndorsementConfigRequest req,
                                                         String actorId,
                                                         String actorEmail) {
        TenantEndorsementConfig snapshot = copy(existing);
        applyRequest(existing, req);
        existing.setUpdatedAt(OffsetDateTime.now());
        existing.setActorId(parseUuid(actorId));
        existing.setActorEmail(actorEmail);
        return repository.save(existing)
                .flatMap(saved -> publishAudit(saved, snapshot, "UPDATE", actorId, actorEmail)
                        .thenReturn(saved));
    }

    private void applyRequest(TenantEndorsementConfig c, UpdateTenantEndorsementConfigRequest req) {
        c.setEnabled(Boolean.TRUE.equals(req.enabled()));
        // Disabling wipes the threshold so a re-enable starts clean.
        if (Boolean.TRUE.equals(req.enabled())) {
            c.setFourEyesThresholdAmount(req.fourEyesThresholdAmount());
            c.setThresholdCurrency(req.thresholdCurrency());
        } else {
            c.setFourEyesThresholdAmount(null);
            c.setThresholdCurrency(null);
        }
    }

    private TenantEndorsementConfig copy(TenantEndorsementConfig src) {
        TenantEndorsementConfig c = new TenantEndorsementConfig();
        c.setId(src.getId());
        c.setTenantId(src.getTenantId());
        c.setEnabled(src.isEnabled());
        c.setFourEyesThresholdAmount(src.getFourEyesThresholdAmount());
        c.setThresholdCurrency(src.getThresholdCurrency());
        c.setCreatedAt(src.getCreatedAt());
        c.setUpdatedAt(src.getUpdatedAt());
        c.setActorId(src.getActorId());
        c.setActorEmail(src.getActorEmail());
        return c;
    }

    private Mono<Void> publishAudit(TenantEndorsementConfig current,
                                    TenantEndorsementConfig previous,
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
                        "EndorsementConfig for tenant " + slug,
                        action,
                        actorId,
                        actorEmail,
                        oldMap,
                        newMap,
                        changed,
                        UUID.randomUUID().toString())));
    }

    private Map<String, Object> toMap(TenantEndorsementConfig c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", c.isEnabled());
        m.put("fourEyesThresholdAmount",
                c.getFourEyesThresholdAmount() != null ? c.getFourEyesThresholdAmount().toPlainString() : null);
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

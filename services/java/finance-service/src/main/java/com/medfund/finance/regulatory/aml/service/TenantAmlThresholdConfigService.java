package com.medfund.finance.regulatory.aml.service;

import com.medfund.finance.regulatory.aml.dto.AddTenantAmlThresholdConfigRequest;
import com.medfund.finance.regulatory.aml.dto.TenantAmlThresholdConfigResponse;
import com.medfund.finance.regulatory.aml.dto.UpdateTenantAmlThresholdConfigRequest;
import com.medfund.finance.regulatory.aml.entity.TenantAmlThresholdConfig;
import com.medfund.finance.regulatory.aml.repository.TenantAmlThresholdConfigRepository;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

/**
 * Admin CRUD for {@code public.tenant_aml_threshold_config} (V175) —
 * per-tenant reportability thresholds consumed by
 * {@code AmlSummaryCalculator} in this service. Mirrors the
 * tenancy-service {@code UsTenantNaicConfigService} shape (Phase 14) —
 * same list / add / update / delete + Rule-2 cross-tenant guard + audit
 * on every mutation.
 *
 * <p>Placed in finance-service (not tenancy-service like the other
 * tenant-admin surfaces) because the AML entity + workflow + calculator
 * all live here — colocating the admin CRUD keeps every AML surface
 * under {@code com.medfund.finance.regulatory.aml.*} and avoids a
 * two-service bounce for a purely compliance-domain read.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantAmlThresholdConfigService {

    private static final String ENTITY_TYPE = "TENANT_AML_THRESHOLD_CONFIG";

    private final TenantAmlThresholdConfigRepository repository;
    private final R2dbcEntityTemplate r2dbcTemplate;
    private final AuditPublisher auditPublisher;

    public Flux<TenantAmlThresholdConfigResponse> list(UUID tenantId) {
        return repository.findByTenantIdOrderByEffectiveFromDesc(tenantId)
                .map(TenantAmlThresholdConfigResponse::from);
    }

    @Transactional
    public Mono<TenantAmlThresholdConfigResponse> add(UUID tenantId,
                                                       AddTenantAmlThresholdConfigRequest req,
                                                       String actorId,
                                                       String actorEmail) {
        requireActor(actorId, actorEmail);
        LocalDate effectiveFrom = req.effectiveFrom() != null ? req.effectiveFrom() : LocalDate.now();

        TenantAmlThresholdConfig row = new TenantAmlThresholdConfig();
        row.setTenantId(tenantId);
        row.setTransactionType(req.transactionType());
        row.setThresholdAmount(req.thresholdAmount());
        row.setCurrency(req.currency().toUpperCase());
        row.setEffectiveFrom(effectiveFrom);
        row.setEffectiveTo(req.effectiveTo());
        row.setSourceNote(req.sourceNote());
        row.setActorId(parseUuid(actorId));
        row.setActorEmail(actorEmail);
        return r2dbcTemplate.insert(row)
                .flatMap(saved -> publishAudit(saved, null, "CREATE", actorId, actorEmail)
                        .thenReturn(TenantAmlThresholdConfigResponse.from(saved)));
    }

    @Transactional
    public Mono<TenantAmlThresholdConfigResponse> update(UUID tenantId, UUID id,
                                                          UpdateTenantAmlThresholdConfigRequest req,
                                                          String actorId, String actorEmail) {
        requireActor(actorId, actorEmail);
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new NoSuchElementException(
                        "Tenant AML threshold config not found: " + id)))
                .flatMap(existing -> {
                    if (!existing.getTenantId().equals(tenantId)) {
                        return Mono.<TenantAmlThresholdConfigResponse>error(new IllegalArgumentException(
                                "Tenant AML threshold config does not belong to tenant"));
                    }
                    TenantAmlThresholdConfig snapshot = copy(existing);
                    if (req.thresholdAmount() != null) {
                        existing.setThresholdAmount(req.thresholdAmount());
                    }
                    existing.setEffectiveTo(req.effectiveTo());
                    if (req.sourceNote() != null) {
                        existing.setSourceNote(req.sourceNote().isBlank() ? null : req.sourceNote());
                    }
                    existing.setUpdatedAt(OffsetDateTime.now());
                    existing.setActorId(parseUuid(actorId));
                    existing.setActorEmail(actorEmail);
                    return repository.save(existing)
                            .flatMap(saved -> publishAudit(saved, snapshot, "UPDATE", actorId, actorEmail)
                                    .thenReturn(TenantAmlThresholdConfigResponse.from(saved)));
                });
    }

    @Transactional
    public Mono<Void> delete(UUID tenantId, UUID id, String actorId, String actorEmail) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new NoSuchElementException(
                        "Tenant AML threshold config not found: " + id)))
                .flatMap(existing -> {
                    if (!existing.getTenantId().equals(tenantId)) {
                        return Mono.<TenantAmlThresholdConfig>error(new IllegalArgumentException(
                                "Tenant AML threshold config does not belong to tenant"));
                    }
                    return repository.delete(existing)
                            .then(publishAudit(existing, copy(existing), "DELETE", actorId, actorEmail))
                            .thenReturn(existing);
                })
                .then();
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static void requireActor(String actorId, String actorEmail) {
        if (actorId == null || actorId.isBlank() || actorEmail == null || actorEmail.isBlank()) {
            throw new IllegalArgumentException("actorId and actorEmail are required");
        }
    }

    private TenantAmlThresholdConfig copy(TenantAmlThresholdConfig src) {
        TenantAmlThresholdConfig c = new TenantAmlThresholdConfig();
        c.setId(src.getId());
        c.setTenantId(src.getTenantId());
        c.setTransactionType(src.getTransactionType());
        c.setThresholdAmount(src.getThresholdAmount());
        c.setCurrency(src.getCurrency());
        c.setEffectiveFrom(src.getEffectiveFrom());
        c.setEffectiveTo(src.getEffectiveTo());
        c.setSourceNote(src.getSourceNote());
        c.setCreatedAt(src.getCreatedAt());
        c.setUpdatedAt(src.getUpdatedAt());
        c.setActorId(src.getActorId());
        c.setActorEmail(src.getActorEmail());
        return c;
    }

    private Mono<Void> publishAudit(TenantAmlThresholdConfig current,
                                    TenantAmlThresholdConfig previous,
                                    String action, String actorId, String actorEmail) {
        Map<String, Object> oldMap = previous != null ? toMap(previous) : null;
        Map<String, Object> newMap = "DELETE".equals(action) ? null : toMap(current);
        String[] changed = "UPDATE".equals(action) && oldMap != null
                ? changedFields(oldMap, newMap) : null;
        String friendly = String.format("AML threshold for tenant %s (%s / %s @ %s)",
                current.getTenantId(),
                current.getTransactionType(),
                current.getCurrency(),
                current.getThresholdAmount() != null ? current.getThresholdAmount().toPlainString() : "?");
        return auditPublisher.publish(AuditEvent.create(
                current.getTenantId().toString(),
                ENTITY_TYPE,
                current.getId().toString(),
                friendly,
                action,
                actorId,
                actorEmail,
                oldMap,
                newMap,
                changed,
                UUID.randomUUID().toString()));
    }

    private static Map<String, Object> toMap(TenantAmlThresholdConfig row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("transactionType", row.getTransactionType());
        m.put("thresholdAmount", row.getThresholdAmount() != null
                ? row.getThresholdAmount().toPlainString() : null);
        m.put("currency", row.getCurrency());
        m.put("effectiveFrom", row.getEffectiveFrom() != null ? row.getEffectiveFrom().toString() : null);
        m.put("effectiveTo", row.getEffectiveTo() != null ? row.getEffectiveTo().toString() : null);
        m.put("sourceNote", row.getSourceNote());
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

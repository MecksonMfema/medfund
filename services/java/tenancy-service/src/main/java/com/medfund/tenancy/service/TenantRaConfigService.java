package com.medfund.tenancy.service;

import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.tenancy.dto.AddTenantRaConfigRequest;
import com.medfund.tenancy.dto.UpdateTenantRaConfigRequest;
import com.medfund.tenancy.entity.Tenant;
import com.medfund.tenancy.entity.TenantRaConfig;
import com.medfund.tenancy.repository.TenantRaConfigRepository;
import com.medfund.tenancy.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

/**
 * CRUD service for {@code public.tenant_ra_config} — per-portfolio IFRS 17
 * Risk Adjustment methodology (CoC or CI) consumed by the ai-service RA
 * compute (Phase 15 §14). Multi-row config modelled after
 * {@link TenantPersistencyBasisService}: list / add / update / delete plus
 * a Rule-2 cross-tenant guard on every mutation.
 *
 * <p>Application-level validation enforces the CoC-vs-CI mutually-exclusive
 * params so callers get a clear 400 rather than a Postgres constraint
 * violation from the row-level CHECK.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantRaConfigService {

    private static final String ENTITY_TYPE = "TENANT_RA_CONFIG";

    private final TenantRaConfigRepository repository;
    private final TenantRepository tenantRepository;
    private final R2dbcEntityTemplate r2dbcTemplate;
    private final AuditPublisher auditPublisher;

    public Flux<TenantRaConfig> list(UUID tenantId) {
        return repository.findByTenantIdOrderByPortfolioIdAscEffectiveFromDesc(tenantId);
    }

    @Transactional
    public Mono<TenantRaConfig> add(UUID tenantId,
                                    AddTenantRaConfigRequest req,
                                    String actorId,
                                    String actorEmail) {
        String methodology = normaliseMethodology(req.methodology());
        validateParams(methodology, req.cocRate(), req.targetConfidenceLevel());
        LocalDate effectiveFrom = req.effectiveFrom() != null ? req.effectiveFrom() : LocalDate.now();

        TenantRaConfig row = new TenantRaConfig();
        row.setTenantId(tenantId);
        row.setPortfolioId(req.portfolioId());
        row.setMethodology(methodology);
        row.setCocRate(req.cocRate());
        row.setTargetConfidenceLevel(req.targetConfidenceLevel());
        row.setSourceNote(req.sourceNote());
        row.setEffectiveFrom(effectiveFrom);
        row.setEffectiveTo(req.effectiveTo());
        row.setUpdatedBy(parseUuid(actorId));
        row.setUpdatedByEmail(actorEmail);
        return r2dbcTemplate.insert(row)
                .flatMap(saved -> publishAudit(saved, null, "CREATE", actorId, actorEmail).thenReturn(saved));
    }

    @Transactional
    public Mono<TenantRaConfig> update(UUID tenantId, UUID id,
                                       UpdateTenantRaConfigRequest req,
                                       String actorId, String actorEmail) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new NoSuchElementException(
                        "RA config not found: " + id)))
                .flatMap(existing -> {
                    if (!existing.getTenantId().equals(tenantId)) {
                        return Mono.<TenantRaConfig>error(new IllegalArgumentException(
                                "RA config does not belong to tenant"));
                    }
                    // Methodology is immutable; whichever param matches the current
                    // methodology is the one that can be updated. The other must stay null.
                    BigDecimal newCoc = "COC".equals(existing.getMethodology()) ? req.cocRate() : null;
                    BigDecimal newCi = "CI".equals(existing.getMethodology()) ? req.targetConfidenceLevel() : null;
                    validateParams(existing.getMethodology(), newCoc, newCi);

                    TenantRaConfig snapshot = copy(existing);
                    existing.setCocRate(newCoc);
                    existing.setTargetConfidenceLevel(newCi);
                    existing.setSourceNote(req.sourceNote());
                    existing.setEffectiveTo(req.effectiveTo());
                    existing.setUpdatedAt(OffsetDateTime.now());
                    existing.setUpdatedBy(parseUuid(actorId));
                    existing.setUpdatedByEmail(actorEmail);
                    return repository.save(existing)
                            .flatMap(saved -> publishAudit(saved, snapshot, "UPDATE", actorId, actorEmail)
                                    .thenReturn(saved));
                });
    }

    @Transactional
    public Mono<Void> delete(UUID tenantId, UUID id, String actorId, String actorEmail) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new NoSuchElementException(
                        "RA config not found: " + id)))
                .flatMap(existing -> {
                    if (!existing.getTenantId().equals(tenantId)) {
                        return Mono.<TenantRaConfig>error(new IllegalArgumentException(
                                "RA config does not belong to tenant"));
                    }
                    return repository.delete(existing)
                            .then(publishAudit(existing, copy(existing), "DELETE", actorId, actorEmail))
                            .thenReturn(existing);
                })
                .then();
    }

    private static String normaliseMethodology(String raw) {
        if (raw == null) throw new IllegalArgumentException("methodology is required");
        String n = raw.trim().toUpperCase();
        if (!"COC".equals(n) && !"CI".equals(n)) {
            throw new IllegalArgumentException(
                    "methodology must be COC or CI, got: " + raw);
        }
        return n;
    }

    private static void validateParams(String methodology, BigDecimal coc, BigDecimal ci) {
        if ("COC".equals(methodology)) {
            if (coc == null) throw new IllegalArgumentException(
                    "cocRate is required when methodology=COC");
            if (ci != null) throw new IllegalArgumentException(
                    "targetConfidenceLevel must be null when methodology=COC");
        } else {
            if (ci == null) throw new IllegalArgumentException(
                    "targetConfidenceLevel is required when methodology=CI");
            if (coc != null) throw new IllegalArgumentException(
                    "cocRate must be null when methodology=CI");
        }
    }

    private TenantRaConfig copy(TenantRaConfig src) {
        TenantRaConfig c = new TenantRaConfig();
        c.setId(src.getId());
        c.setTenantId(src.getTenantId());
        c.setPortfolioId(src.getPortfolioId());
        c.setMethodology(src.getMethodology());
        c.setCocRate(src.getCocRate());
        c.setTargetConfidenceLevel(src.getTargetConfidenceLevel());
        c.setSourceNote(src.getSourceNote());
        c.setEffectiveFrom(src.getEffectiveFrom());
        c.setEffectiveTo(src.getEffectiveTo());
        c.setCreatedAt(src.getCreatedAt());
        c.setUpdatedAt(src.getUpdatedAt());
        c.setUpdatedBy(src.getUpdatedBy());
        c.setUpdatedByEmail(src.getUpdatedByEmail());
        return c;
    }

    private Mono<Void> publishAudit(TenantRaConfig current,
                                    TenantRaConfig previous,
                                    String action, String actorId, String actorEmail) {
        Map<String, Object> oldMap = previous != null ? toMap(previous) : null;
        Map<String, Object> newMap = "DELETE".equals(action) ? null : toMap(current);
        String[] changed = "UPDATE".equals(action) && oldMap != null
                ? changedFields(oldMap, newMap) : null;

        return tenantRepository.findById(current.getTenantId())
                .map(Tenant::getSlug)
                .defaultIfEmpty("unknown")
                .flatMap(slug -> auditPublisher.publish(AuditEvent.create(
                        current.getTenantId().toString(),
                        ENTITY_TYPE,
                        current.getId().toString(),
                        String.format("RaConfig for tenant %s portfolio %s (%s)",
                                slug, current.getPortfolioId(), current.getMethodology()),
                        action,
                        actorId,
                        actorEmail,
                        oldMap,
                        newMap,
                        changed,
                        UUID.randomUUID().toString())));
    }

    private Map<String, Object> toMap(TenantRaConfig row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("portfolioId", row.getPortfolioId() != null ? row.getPortfolioId().toString() : null);
        m.put("methodology", row.getMethodology());
        m.put("cocRate", row.getCocRate() != null ? row.getCocRate().toPlainString() : null);
        m.put("targetConfidenceLevel", row.getTargetConfidenceLevel() != null
                ? row.getTargetConfidenceLevel().toPlainString() : null);
        m.put("sourceNote", row.getSourceNote());
        m.put("effectiveFrom", row.getEffectiveFrom() != null ? row.getEffectiveFrom().toString() : null);
        m.put("effectiveTo", row.getEffectiveTo() != null ? row.getEffectiveTo().toString() : null);
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

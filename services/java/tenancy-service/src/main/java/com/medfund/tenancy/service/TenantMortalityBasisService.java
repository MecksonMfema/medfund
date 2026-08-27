package com.medfund.tenancy.service;

import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.insurance.InsuranceLine;
import com.medfund.tenancy.dto.AddTenantMortalityBasisRequest;
import com.medfund.tenancy.dto.UpdateTenantMortalityBasisRequest;
import com.medfund.tenancy.entity.Tenant;
import com.medfund.tenancy.entity.TenantMortalityBasis;
import com.medfund.tenancy.repository.TenantMortalityBasisRepository;
import com.medfund.tenancy.repository.TenantRepository;
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
 * CRUD service for {@code public.tenant_mortality_basis} — one row per
 * (insurance_line, effective_from) selecting the mortality reference table
 * (e.g. {@code A1949_52}, {@code SA85_90}) and its local multiplier. The
 * chosen basis name is loaded from an ai-service YAML at compute-time
 * (Phase 6/13); this table only records the tenant's pick + multiplier.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantMortalityBasisService {

    private static final String ENTITY_TYPE = "TENANT_MORTALITY_BASIS";

    private final TenantMortalityBasisRepository repository;
    private final TenantRepository tenantRepository;
    private final R2dbcEntityTemplate r2dbcTemplate;
    private final AuditPublisher auditPublisher;

    public Flux<TenantMortalityBasis> list(UUID tenantId) {
        return repository.findByTenantIdOrderByInsuranceLineAscEffectiveFromDesc(tenantId);
    }

    @Transactional
    public Mono<TenantMortalityBasis> add(UUID tenantId,
                                          AddTenantMortalityBasisRequest req,
                                          String actorId, String actorEmail) {
        if (!InsuranceLine.isKnown(req.insuranceLine())) {
            return Mono.error(new IllegalArgumentException("Unknown insurance line: " + req.insuranceLine()));
        }
        LocalDate effectiveFrom = req.effectiveFrom() != null ? req.effectiveFrom() : LocalDate.now();
        TenantMortalityBasis row = new TenantMortalityBasis();
        row.setTenantId(tenantId);
        row.setInsuranceLine(req.insuranceLine().toUpperCase());
        row.setBasisName(req.basisName());
        row.setMortalityMultiplier(req.mortalityMultiplier());
        row.setEffectiveFrom(effectiveFrom);
        row.setEffectiveTo(req.effectiveTo());
        row.setUpdatedBy(parseUuid(actorId));
        row.setUpdatedByEmail(actorEmail);
        return r2dbcTemplate.insert(row)
                .flatMap(saved -> publishAudit(saved, null, "CREATE", actorId, actorEmail).thenReturn(saved));
    }

    @Transactional
    public Mono<TenantMortalityBasis> update(UUID tenantId, UUID id,
                                             UpdateTenantMortalityBasisRequest req,
                                             String actorId, String actorEmail) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new NoSuchElementException(
                        "Mortality basis not found: " + id)))
                .flatMap(existing -> {
                    if (!existing.getTenantId().equals(tenantId)) {
                        return Mono.<TenantMortalityBasis>error(new IllegalArgumentException(
                                "Mortality basis does not belong to tenant"));
                    }
                    TenantMortalityBasis snapshot = copy(existing);
                    existing.setBasisName(req.basisName());
                    existing.setMortalityMultiplier(req.mortalityMultiplier());
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
                        "Mortality basis not found: " + id)))
                .flatMap(existing -> {
                    if (!existing.getTenantId().equals(tenantId)) {
                        return Mono.<TenantMortalityBasis>error(new IllegalArgumentException(
                                "Mortality basis does not belong to tenant"));
                    }
                    return repository.delete(existing)
                            .then(publishAudit(existing, copy(existing), "DELETE", actorId, actorEmail))
                            .thenReturn(existing);
                })
                .then();
    }

    private TenantMortalityBasis copy(TenantMortalityBasis src) {
        TenantMortalityBasis c = new TenantMortalityBasis();
        c.setId(src.getId());
        c.setTenantId(src.getTenantId());
        c.setInsuranceLine(src.getInsuranceLine());
        c.setBasisName(src.getBasisName());
        c.setMortalityMultiplier(src.getMortalityMultiplier());
        c.setEffectiveFrom(src.getEffectiveFrom());
        c.setEffectiveTo(src.getEffectiveTo());
        c.setCreatedAt(src.getCreatedAt());
        c.setUpdatedAt(src.getUpdatedAt());
        c.setUpdatedBy(src.getUpdatedBy());
        c.setUpdatedByEmail(src.getUpdatedByEmail());
        return c;
    }

    private Mono<Void> publishAudit(TenantMortalityBasis current,
                                    TenantMortalityBasis previous,
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
                        String.format("MortalityBasis for tenant %s %s:%s",
                                slug, current.getInsuranceLine(), current.getBasisName()),
                        action,
                        actorId,
                        actorEmail,
                        oldMap,
                        newMap,
                        changed,
                        UUID.randomUUID().toString())));
    }

    private Map<String, Object> toMap(TenantMortalityBasis row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("insuranceLine", row.getInsuranceLine());
        m.put("basisName", row.getBasisName());
        m.put("mortalityMultiplier", row.getMortalityMultiplier() != null
                ? row.getMortalityMultiplier().toPlainString() : null);
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

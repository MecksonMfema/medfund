package com.medfund.tenancy.service;

import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.insurance.InsuranceLine;
import com.medfund.tenancy.dto.AddTenantMorbidityBasisRequest;
import com.medfund.tenancy.dto.UpdateTenantMorbidityBasisRequest;
import com.medfund.tenancy.entity.Tenant;
import com.medfund.tenancy.entity.TenantMorbidityBasis;
import com.medfund.tenancy.repository.TenantMorbidityBasisRepository;
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
 * CRUD service for {@code public.tenant_morbidity_basis} — one row per
 * (insurance_line, effective_from) selecting the morbidity/incidence
 * reference table (CIDA, GLTD87, etc.) and its local multiplier for the
 * actuarial MORBIDITY_STUDY.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantMorbidityBasisService {

    private static final String ENTITY_TYPE = "TENANT_MORBIDITY_BASIS";

    private final TenantMorbidityBasisRepository repository;
    private final TenantRepository tenantRepository;
    private final R2dbcEntityTemplate r2dbcTemplate;
    private final AuditPublisher auditPublisher;

    public Flux<TenantMorbidityBasis> list(UUID tenantId) {
        return repository.findByTenantIdOrderByInsuranceLineAscEffectiveFromDesc(tenantId);
    }

    @Transactional
    public Mono<TenantMorbidityBasis> add(UUID tenantId,
                                          AddTenantMorbidityBasisRequest req,
                                          String actorId, String actorEmail) {
        if (!InsuranceLine.isKnown(req.insuranceLine())) {
            return Mono.error(new IllegalArgumentException("Unknown insurance line: " + req.insuranceLine()));
        }
        LocalDate effectiveFrom = req.effectiveFrom() != null ? req.effectiveFrom() : LocalDate.now();
        TenantMorbidityBasis row = new TenantMorbidityBasis();
        row.setTenantId(tenantId);
        row.setInsuranceLine(req.insuranceLine().toUpperCase());
        row.setBasisName(req.basisName());
        row.setMorbidityMultiplier(req.morbidityMultiplier());
        row.setEffectiveFrom(effectiveFrom);
        row.setEffectiveTo(req.effectiveTo());
        row.setUpdatedBy(parseUuid(actorId));
        row.setUpdatedByEmail(actorEmail);
        return r2dbcTemplate.insert(row)
                .flatMap(saved -> publishAudit(saved, null, "CREATE", actorId, actorEmail).thenReturn(saved));
    }

    @Transactional
    public Mono<TenantMorbidityBasis> update(UUID tenantId, UUID id,
                                             UpdateTenantMorbidityBasisRequest req,
                                             String actorId, String actorEmail) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new NoSuchElementException(
                        "Morbidity basis not found: " + id)))
                .flatMap(existing -> {
                    if (!existing.getTenantId().equals(tenantId)) {
                        return Mono.<TenantMorbidityBasis>error(new IllegalArgumentException(
                                "Morbidity basis does not belong to tenant"));
                    }
                    TenantMorbidityBasis snapshot = copy(existing);
                    existing.setBasisName(req.basisName());
                    existing.setMorbidityMultiplier(req.morbidityMultiplier());
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
                        "Morbidity basis not found: " + id)))
                .flatMap(existing -> {
                    if (!existing.getTenantId().equals(tenantId)) {
                        return Mono.<TenantMorbidityBasis>error(new IllegalArgumentException(
                                "Morbidity basis does not belong to tenant"));
                    }
                    return repository.delete(existing)
                            .then(publishAudit(existing, copy(existing), "DELETE", actorId, actorEmail))
                            .thenReturn(existing);
                })
                .then();
    }

    private TenantMorbidityBasis copy(TenantMorbidityBasis src) {
        TenantMorbidityBasis c = new TenantMorbidityBasis();
        c.setId(src.getId());
        c.setTenantId(src.getTenantId());
        c.setInsuranceLine(src.getInsuranceLine());
        c.setBasisName(src.getBasisName());
        c.setMorbidityMultiplier(src.getMorbidityMultiplier());
        c.setEffectiveFrom(src.getEffectiveFrom());
        c.setEffectiveTo(src.getEffectiveTo());
        c.setCreatedAt(src.getCreatedAt());
        c.setUpdatedAt(src.getUpdatedAt());
        c.setUpdatedBy(src.getUpdatedBy());
        c.setUpdatedByEmail(src.getUpdatedByEmail());
        return c;
    }

    private Mono<Void> publishAudit(TenantMorbidityBasis current,
                                    TenantMorbidityBasis previous,
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
                        String.format("MorbidityBasis for tenant %s %s:%s",
                                slug, current.getInsuranceLine(), current.getBasisName()),
                        action,
                        actorId,
                        actorEmail,
                        oldMap,
                        newMap,
                        changed,
                        UUID.randomUUID().toString())));
    }

    private Map<String, Object> toMap(TenantMorbidityBasis row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("insuranceLine", row.getInsuranceLine());
        m.put("basisName", row.getBasisName());
        m.put("morbidityMultiplier", row.getMorbidityMultiplier() != null
                ? row.getMorbidityMultiplier().toPlainString() : null);
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

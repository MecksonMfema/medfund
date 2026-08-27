package com.medfund.tenancy.service;

import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.insurance.InsuranceLine;
import com.medfund.tenancy.dto.AddTenantPersistencyBasisRequest;
import com.medfund.tenancy.dto.UpdateTenantPersistencyBasisRequest;
import com.medfund.tenancy.entity.Tenant;
import com.medfund.tenancy.entity.TenantPersistencyBasis;
import com.medfund.tenancy.repository.TenantPersistencyBasisRepository;
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
 * CRUD service for {@code public.tenant_persistency_basis} — per-tenant
 * expected-retention curves consumed by the actuarial PERSISTENCY_STUDY
 * report. Multi-row config (unlike the single-row {@link TenantEndorsementConfigService})
 * modeled after {@link TenantCurrencyService}: list / add / update / delete.
 *
 * <p>Every mutation emits an audit event whose {@code entityName} names the
 * tenant slug + insurance line + cohort — never the raw UUID, per
 * {@code feedback_audit_entity_name}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantPersistencyBasisService {

    private static final String ENTITY_TYPE = "TENANT_PERSISTENCY_BASIS";

    private final TenantPersistencyBasisRepository repository;
    private final TenantRepository tenantRepository;
    private final R2dbcEntityTemplate r2dbcTemplate;
    private final AuditPublisher auditPublisher;

    public Flux<TenantPersistencyBasis> list(UUID tenantId) {
        return repository.findByTenantIdOrderByInsuranceLineAscCohortMonthsAsc(tenantId);
    }

    @Transactional
    public Mono<TenantPersistencyBasis> add(UUID tenantId,
                                            AddTenantPersistencyBasisRequest req,
                                            String actorId,
                                            String actorEmail) {
        validateLine(req.insuranceLine());
        LocalDate effectiveFrom = req.effectiveFrom() != null ? req.effectiveFrom() : LocalDate.now();
        TenantPersistencyBasis row = new TenantPersistencyBasis();
        row.setTenantId(tenantId);
        row.setInsuranceLine(req.insuranceLine().toUpperCase());
        row.setCohortMonths(req.cohortMonths());
        row.setExpectedRetentionPct(req.expectedRetentionPct());
        row.setSourceNote(req.sourceNote());
        row.setEffectiveFrom(effectiveFrom);
        row.setEffectiveTo(req.effectiveTo());
        row.setUpdatedBy(parseUuid(actorId));
        row.setUpdatedByEmail(actorEmail);
        return r2dbcTemplate.insert(row)
                .flatMap(saved -> publishAudit(saved, null, "CREATE", actorId, actorEmail).thenReturn(saved));
    }

    @Transactional
    public Mono<TenantPersistencyBasis> update(UUID tenantId, UUID id,
                                               UpdateTenantPersistencyBasisRequest req,
                                               String actorId, String actorEmail) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new NoSuchElementException(
                        "Persistency basis not found: " + id)))
                .flatMap(existing -> {
                    if (!existing.getTenantId().equals(tenantId)) {
                        return Mono.<TenantPersistencyBasis>error(new IllegalArgumentException(
                                "Persistency basis does not belong to tenant"));
                    }
                    TenantPersistencyBasis snapshot = copy(existing);
                    existing.setExpectedRetentionPct(req.expectedRetentionPct());
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
                        "Persistency basis not found: " + id)))
                .flatMap(existing -> {
                    if (!existing.getTenantId().equals(tenantId)) {
                        return Mono.<TenantPersistencyBasis>error(new IllegalArgumentException(
                                "Persistency basis does not belong to tenant"));
                    }
                    return repository.delete(existing)
                            .then(publishAudit(existing, copy(existing), "DELETE", actorId, actorEmail))
                            .thenReturn(existing);
                })
                .then();
    }

    private void validateLine(String code) {
        if (!InsuranceLine.isKnown(code)) {
            throw new IllegalArgumentException("Unknown insurance line: " + code);
        }
    }

    private TenantPersistencyBasis copy(TenantPersistencyBasis src) {
        TenantPersistencyBasis c = new TenantPersistencyBasis();
        c.setId(src.getId());
        c.setTenantId(src.getTenantId());
        c.setInsuranceLine(src.getInsuranceLine());
        c.setCohortMonths(src.getCohortMonths());
        c.setExpectedRetentionPct(src.getExpectedRetentionPct());
        c.setSourceNote(src.getSourceNote());
        c.setEffectiveFrom(src.getEffectiveFrom());
        c.setEffectiveTo(src.getEffectiveTo());
        c.setCreatedAt(src.getCreatedAt());
        c.setUpdatedAt(src.getUpdatedAt());
        c.setUpdatedBy(src.getUpdatedBy());
        c.setUpdatedByEmail(src.getUpdatedByEmail());
        return c;
    }

    private Mono<Void> publishAudit(TenantPersistencyBasis current,
                                    TenantPersistencyBasis previous,
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
                        String.format("PersistencyBasis for tenant %s %s@%dm",
                                slug, current.getInsuranceLine(), current.getCohortMonths()),
                        action,
                        actorId,
                        actorEmail,
                        oldMap,
                        newMap,
                        changed,
                        UUID.randomUUID().toString())));
    }

    private Map<String, Object> toMap(TenantPersistencyBasis row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("insuranceLine", row.getInsuranceLine());
        m.put("cohortMonths", row.getCohortMonths());
        m.put("expectedRetentionPct", row.getExpectedRetentionPct() != null
                ? row.getExpectedRetentionPct().toPlainString() : null);
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

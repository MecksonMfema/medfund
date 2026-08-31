package com.medfund.tenancy.service;

import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.tenancy.dto.AddTenantTaxConfigRequest;
import com.medfund.tenancy.dto.UpdateTenantTaxConfigRequest;
import com.medfund.tenancy.entity.Tenant;
import com.medfund.tenancy.entity.TenantTaxConfig;
import com.medfund.tenancy.repository.TenantRepository;
import com.medfund.tenancy.repository.TenantTaxConfigRepository;
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
 * CRUD service for {@code public.tenant_tax_config} — per-tenant statutory
 * tax rates consumed by the VAT Return (Phase 20) + TaxWithheldReturn
 * (Phase 21) shapers. Multi-row effective-dated config mirroring
 * {@link UsTenantNaicConfigService}: list / add / update / delete plus a
 * Rule-2 cross-tenant guard on every mutation.
 *
 * <p>Application-level validation duplicates the DB CHECK regexes so
 * callers get a clean 400 rather than a Postgres constraint violation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantTaxConfigService {

    private static final String ENTITY_TYPE = "TENANT_TAX_CONFIG";

    private final TenantTaxConfigRepository repository;
    private final TenantRepository tenantRepository;
    private final R2dbcEntityTemplate r2dbcTemplate;
    private final AuditPublisher auditPublisher;

    public Flux<TenantTaxConfig> list(UUID tenantId) {
        return repository.findByTenantIdOrderByEffectiveFromDesc(tenantId);
    }

    public Flux<TenantTaxConfig> listByType(UUID tenantId, String taxType) {
        if (taxType == null || taxType.isBlank()) {
            return list(tenantId);
        }
        return repository.findByTenantIdAndTaxTypeOrderByEffectiveFromDesc(
                tenantId, taxType.trim().toUpperCase());
    }

    @Transactional
    public Mono<TenantTaxConfig> add(UUID tenantId,
                                     AddTenantTaxConfigRequest req,
                                     String actorId,
                                     String actorEmail) {
        String country = requireBlank(req.countryCode(), "countryCode").toUpperCase();
        String taxType = requireBlank(req.taxType(), "taxType").toUpperCase();
        String category = requireBlank(req.transactionCategory(), "transactionCategory").toUpperCase();
        String currency = requireBlank(req.currency(), "currency").toUpperCase();
        LocalDate effectiveFrom = req.effectiveFrom() != null ? req.effectiveFrom() : LocalDate.now();

        TenantTaxConfig row = new TenantTaxConfig();
        row.setTenantId(tenantId);
        row.setCountryCode(country);
        row.setTaxType(taxType);
        row.setTransactionCategory(category);
        row.setCurrency(currency);
        row.setRate(req.rate());
        row.setRegistered(req.registered() == null || req.registered());
        row.setRegistrationNumber(trimOrNull(req.registrationNumber()));
        row.setEffectiveFrom(effectiveFrom);
        row.setEffectiveTo(req.effectiveTo());
        row.setSourceNote(trimOrNull(req.sourceNote()));
        row.setUpdatedBy(parseUuid(actorId));
        row.setUpdatedByEmail(actorEmail);
        return r2dbcTemplate.insert(row)
                .flatMap(saved -> publishAudit(saved, null, "CREATE", actorId, actorEmail).thenReturn(saved));
    }

    @Transactional
    public Mono<TenantTaxConfig> update(UUID tenantId, UUID id,
                                        UpdateTenantTaxConfigRequest req,
                                        String actorId, String actorEmail) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new NoSuchElementException(
                        "Tenant tax config not found: " + id)))
                .flatMap(existing -> {
                    if (!existing.getTenantId().equals(tenantId)) {
                        return Mono.<TenantTaxConfig>error(new IllegalArgumentException(
                                "Tenant tax config does not belong to tenant"));
                    }
                    TenantTaxConfig snapshot = copy(existing);
                    if (req.rate() != null) {
                        existing.setRate(req.rate());
                    }
                    if (req.registered() != null) {
                        existing.setRegistered(req.registered());
                    }
                    if (req.registrationNumber() != null) {
                        // Blank clears; a caller that wants to preserve the number omits the field.
                        existing.setRegistrationNumber(req.registrationNumber().isBlank()
                                ? null : req.registrationNumber().trim());
                    }
                    existing.setEffectiveTo(req.effectiveTo());
                    if (req.sourceNote() != null) {
                        existing.setSourceNote(req.sourceNote().isBlank() ? null : req.sourceNote().trim());
                    }
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
                        "Tenant tax config not found: " + id)))
                .flatMap(existing -> {
                    if (!existing.getTenantId().equals(tenantId)) {
                        return Mono.<TenantTaxConfig>error(new IllegalArgumentException(
                                "Tenant tax config does not belong to tenant"));
                    }
                    return repository.delete(existing)
                            .then(publishAudit(existing, copy(existing), "DELETE", actorId, actorEmail))
                            .thenReturn(existing);
                })
                .then();
    }

    private static String requireBlank(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return raw.trim();
    }

    private static String trimOrNull(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        return t.isEmpty() ? null : t;
    }

    private TenantTaxConfig copy(TenantTaxConfig src) {
        TenantTaxConfig c = new TenantTaxConfig();
        c.setId(src.getId());
        c.setTenantId(src.getTenantId());
        c.setCountryCode(src.getCountryCode());
        c.setTaxType(src.getTaxType());
        c.setTransactionCategory(src.getTransactionCategory());
        c.setCurrency(src.getCurrency());
        c.setRate(src.getRate());
        c.setRegistered(src.isRegistered());
        c.setRegistrationNumber(src.getRegistrationNumber());
        c.setEffectiveFrom(src.getEffectiveFrom());
        c.setEffectiveTo(src.getEffectiveTo());
        c.setSourceNote(src.getSourceNote());
        c.setCreatedAt(src.getCreatedAt());
        c.setUpdatedAt(src.getUpdatedAt());
        c.setUpdatedBy(src.getUpdatedBy());
        c.setUpdatedByEmail(src.getUpdatedByEmail());
        return c;
    }

    private Mono<Void> publishAudit(TenantTaxConfig current,
                                    TenantTaxConfig previous,
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
                        String.format("Tax rate for tenant %s (%s %s / %s / %s @ %s)",
                                slug,
                                current.getCountryCode(),
                                current.getTaxType(),
                                current.getTransactionCategory(),
                                current.getCurrency(),
                                current.getRate() != null ? current.getRate().toPlainString() : "?"),
                        action,
                        actorId,
                        actorEmail,
                        oldMap,
                        newMap,
                        changed,
                        UUID.randomUUID().toString())));
    }

    private Map<String, Object> toMap(TenantTaxConfig row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("countryCode", row.getCountryCode());
        m.put("taxType", row.getTaxType());
        m.put("transactionCategory", row.getTransactionCategory());
        m.put("currency", row.getCurrency());
        m.put("rate", row.getRate() != null ? row.getRate().toPlainString() : null);
        m.put("registered", row.isRegistered());
        m.put("registrationNumber", row.getRegistrationNumber());
        m.put("effectiveFrom", row.getEffectiveFrom() != null ? row.getEffectiveFrom().toString() : null);
        m.put("effectiveTo", row.getEffectiveTo() != null ? row.getEffectiveTo().toString() : null);
        m.put("sourceNote", row.getSourceNote());
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

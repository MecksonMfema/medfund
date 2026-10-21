package com.medfund.tenancy.service;

import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.tenancy.dto.AddUsTenantNaicConfigRequest;
import com.medfund.tenancy.dto.UpdateUsTenantNaicConfigRequest;
import com.medfund.tenancy.entity.Tenant;
import com.medfund.tenancy.entity.UsTenantNaicConfig;
import com.medfund.tenancy.repository.TenantRepository;
import com.medfund.tenancy.repository.UsTenantNaicConfigRepository;
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
 * CRUD service for {@code public.us_tenant_naic_config} — per-tenant NAIC
 * company identity consumed by the NAIC Schedule P / F shapers (Phase 12
 * / 13). Multi-row effective-dated config mirroring
 * {@link TenantRaConfigService}: list / add / update / delete plus a
 * Rule-2 cross-tenant guard on every mutation.
 *
 * <p>Application-level validation duplicates the DB CHECK regexes so
 * callers get a clean 400 rather than a Postgres constraint violation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UsTenantNaicConfigService {

    private static final String ENTITY_TYPE = "US_TENANT_NAIC_CONFIG";

    private final UsTenantNaicConfigRepository repository;
    private final TenantRepository tenantRepository;
    private final R2dbcEntityTemplate r2dbcTemplate;
    private final AuditPublisher auditPublisher;

    public Flux<UsTenantNaicConfig> list(UUID tenantId) {
        return repository.findByTenantIdOrderByEffectiveFromDesc(tenantId);
    }

    @Transactional
    public Mono<UsTenantNaicConfig> add(UUID tenantId,
                                        AddUsTenantNaicConfigRequest req,
                                        String actorId,
                                        String actorEmail) {
        String state = requireBlankSafe(req.stateDomicile(), "stateDomicile").toUpperCase();
        String company = requireBlankSafe(req.naicCompanyCode(), "naicCompanyCode");
        String fein = requireBlankSafe(req.fein(), "fein");
        String group = trimOrNull(req.naicGroupCode());
        LocalDate effectiveFrom = req.effectiveFrom() != null ? req.effectiveFrom() : LocalDate.now();

        UsTenantNaicConfig row = new UsTenantNaicConfig();
        row.setTenantId(tenantId);
        row.setStateDomicile(state);
        row.setNaicCompanyCode(company);
        row.setNaicGroupCode(group);
        row.setFein(fein);
        row.setEffectiveFrom(effectiveFrom);
        row.setEffectiveTo(req.effectiveTo());
        row.setSourceNote(req.sourceNote());
        row.setUpdatedBy(parseUuid(actorId));
        row.setUpdatedByEmail(actorEmail);
        return r2dbcTemplate.insert(row)
                .flatMap(saved -> publishAudit(saved, null, "CREATE", actorId, actorEmail).thenReturn(saved));
    }

    @Transactional
    public Mono<UsTenantNaicConfig> update(UUID tenantId, UUID id,
                                           UpdateUsTenantNaicConfigRequest req,
                                           String actorId, String actorEmail) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new NoSuchElementException(
                        "US tenant NAIC config not found: " + id)))
                .flatMap(existing -> {
                    if (!existing.getTenantId().equals(tenantId)) {
                        return Mono.<UsTenantNaicConfig>error(new IllegalArgumentException(
                                "US tenant NAIC config does not belong to tenant"));
                    }
                    UsTenantNaicConfig snapshot = copy(existing);
                    if (req.stateDomicile() != null) {
                        existing.setStateDomicile(req.stateDomicile().toUpperCase());
                    }
                    if (req.naicCompanyCode() != null) {
                        existing.setNaicCompanyCode(req.naicCompanyCode());
                    }
                    if (req.naicGroupCode() != null) {
                        // Blank clears; regex validator already rejects malformed non-blank input.
                        existing.setNaicGroupCode(req.naicGroupCode().isBlank() ? null : req.naicGroupCode());
                    }
                    if (req.fein() != null) {
                        existing.setFein(req.fein());
                    }
                    existing.setEffectiveTo(req.effectiveTo());
                    if (req.sourceNote() != null) {
                        existing.setSourceNote(req.sourceNote().isBlank() ? null : req.sourceNote());
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
                        "US tenant NAIC config not found: " + id)))
                .flatMap(existing -> {
                    if (!existing.getTenantId().equals(tenantId)) {
                        return Mono.<UsTenantNaicConfig>error(new IllegalArgumentException(
                                "US tenant NAIC config does not belong to tenant"));
                    }
                    return repository.delete(existing)
                            .then(publishAudit(existing, copy(existing), "DELETE", actorId, actorEmail))
                            .thenReturn(existing);
                })
                .then();
    }

    private static String requireBlankSafe(String raw, String field) {
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

    private UsTenantNaicConfig copy(UsTenantNaicConfig src) {
        UsTenantNaicConfig c = new UsTenantNaicConfig();
        c.setId(src.getId());
        c.setTenantId(src.getTenantId());
        c.setStateDomicile(src.getStateDomicile());
        c.setNaicCompanyCode(src.getNaicCompanyCode());
        c.setNaicGroupCode(src.getNaicGroupCode());
        c.setFein(src.getFein());
        c.setEffectiveFrom(src.getEffectiveFrom());
        c.setEffectiveTo(src.getEffectiveTo());
        c.setSourceNote(src.getSourceNote());
        c.setCreatedAt(src.getCreatedAt());
        c.setUpdatedAt(src.getUpdatedAt());
        c.setUpdatedBy(src.getUpdatedBy());
        c.setUpdatedByEmail(src.getUpdatedByEmail());
        return c;
    }

    private Mono<Void> publishAudit(UsTenantNaicConfig current,
                                    UsTenantNaicConfig previous,
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
                        String.format("NAIC identity for tenant %s (%s / %s / %s)",
                                slug,
                                current.getStateDomicile(),
                                current.getNaicCompanyCode(),
                                current.getFein()),
                        action,
                        actorId,
                        actorEmail,
                        oldMap,
                        newMap,
                        changed,
                        UUID.randomUUID().toString())));
    }

    private Map<String, Object> toMap(UsTenantNaicConfig row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stateDomicile", row.getStateDomicile());
        m.put("naicCompanyCode", row.getNaicCompanyCode());
        m.put("naicGroupCode", row.getNaicGroupCode());
        m.put("fein", row.getFein());
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

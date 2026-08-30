package com.medfund.tenancy.service;

import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.tenancy.dto.AddTenantYieldCurveRequest;
import com.medfund.tenancy.dto.UpdateTenantYieldCurveRequest;
import com.medfund.tenancy.entity.Tenant;
import com.medfund.tenancy.entity.TenantYieldCurve;
import com.medfund.tenancy.repository.TenantRepository;
import com.medfund.tenancy.repository.TenantYieldCurveRepository;
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
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * CRUD service for {@code public.tenant_yield_curve} — per-tenant yield
 * curve points used by IFRS 17 GMM / VFA discounting (Phase 15 §11-16).
 * Multi-row config: each curve is many rows (one per tenor) sharing a
 * currency and effective_from.
 *
 * <p>Source is validated against the fixed set kept in sync with V153's
 * CHECK constraint. Rows written by tenant admins default to
 * {@code ADMIN}; Phase 24 market-data-service auto-writes carry
 * {@code RBZ_AUTO} or {@code SARB_AUTO}; the Phase 6 backfill task
 * uses {@code BACKFILL_FALLBACK}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantYieldCurveService {

    private static final String ENTITY_TYPE = "TENANT_YIELD_CURVE";
    private static final Set<String> ALLOWED_SOURCES =
            Set.of("ADMIN", "RBZ_AUTO", "SARB_AUTO", "BACKFILL_FALLBACK");

    private final TenantYieldCurveRepository repository;
    private final TenantRepository tenantRepository;
    private final R2dbcEntityTemplate r2dbcTemplate;
    private final AuditPublisher auditPublisher;

    public Flux<TenantYieldCurve> list(UUID tenantId) {
        return repository.findByTenantIdOrderByCurrencyAscEffectiveFromDescTenorMonthsAsc(tenantId);
    }

    public Flux<TenantYieldCurve> listForCurrency(UUID tenantId, String currency) {
        return repository.findByTenantIdAndCurrencyOrderByEffectiveFromDescTenorMonthsAsc(
                tenantId, currency.toUpperCase());
    }

    /**
     * Upsert one auto-fetched tenor point (Phase 15 §24). Called from the
     * {@link com.medfund.tenancy.marketdata.YieldCurveConsumer} for every
     * point in a curve delivered by the Go market-data-service. Preserves
     * any pre-existing ADMIN-source row for the same tuple — auto-fetched
     * rows only overwrite prior AUTO rows on the same (currency, tenor,
     * effective_from) tuple.
     *
     * <p>Audit envelope is deliberately actor-less on the auto-fetch path:
     * the actor is the market-data-service daemon, recorded via a
     * synthetic {@code "system@market-data-service"} actor email so the
     * event listing stays consistent with {@code feedback_audit_actor_email}
     * (never null) without pretending a human clicked a button.
     */
    @Transactional
    public Mono<TenantYieldCurve> upsertAutoFetched(UUID tenantId,
                                                    String currency,
                                                    int tenorMonths,
                                                    java.math.BigDecimal spotRate,
                                                    String source,
                                                    LocalDate effectiveFrom) {
        String upperCurrency = currency.toUpperCase();
        String upperSource = normaliseSource(source);
        LocalDate eff = effectiveFrom != null ? effectiveFrom : LocalDate.now();

        return repository
                .findByTenantIdAndCurrencyOrderByEffectiveFromDescTenorMonthsAsc(tenantId, upperCurrency)
                .filter(row -> row.getTenorMonths() != null
                        && row.getTenorMonths() == tenorMonths
                        && eff.equals(row.getEffectiveFrom()))
                .next()
                .flatMap(existing -> {
                    // Never overwrite an ADMIN or BACKFILL row with an AUTO row —
                    // admin intent + backfill fallback both trump auto-fetch.
                    if ("ADMIN".equals(existing.getSource())
                            || "BACKFILL_FALLBACK".equals(existing.getSource())) {
                        log.debug("[market-data] preserved {} row currency={} tenor={} — skipping auto upsert",
                                existing.getSource(), upperCurrency, tenorMonths);
                        return Mono.just(existing);
                    }
                    TenantYieldCurve snapshot = copy(existing);
                    existing.setSpotRate(spotRate);
                    existing.setSource(upperSource);
                    existing.setUpdatedAt(OffsetDateTime.now());
                    existing.setUpdatedByEmail("system@market-data-service");
                    return repository.save(existing)
                            .flatMap(saved -> publishAudit(saved, snapshot, "UPDATE", null,
                                    "system@market-data-service").thenReturn(saved));
                })
                .switchIfEmpty(Mono.defer(() -> {
                    TenantYieldCurve fresh = new TenantYieldCurve();
                    fresh.setTenantId(tenantId);
                    fresh.setCurrency(upperCurrency);
                    fresh.setTenorMonths(tenorMonths);
                    fresh.setSpotRate(spotRate);
                    fresh.setSource(upperSource);
                    fresh.setEffectiveFrom(eff);
                    fresh.setUpdatedByEmail("system@market-data-service");
                    return r2dbcTemplate.insert(fresh)
                            .flatMap(saved -> publishAudit(saved, null, "CREATE", null,
                                    "system@market-data-service").thenReturn(saved));
                }));
    }

    @Transactional
    public Mono<TenantYieldCurve> add(UUID tenantId,
                                      AddTenantYieldCurveRequest req,
                                      String actorId,
                                      String actorEmail) {
        String source = normaliseSource(req.source());
        LocalDate effectiveFrom = req.effectiveFrom() != null ? req.effectiveFrom() : LocalDate.now();

        TenantYieldCurve row = new TenantYieldCurve();
        row.setTenantId(tenantId);
        row.setCurrency(req.currency().toUpperCase());
        row.setTenorMonths(req.tenorMonths());
        row.setSpotRate(req.spotRate());
        row.setSource(source);
        row.setEffectiveFrom(effectiveFrom);
        row.setEffectiveTo(req.effectiveTo());
        row.setUpdatedBy(parseUuid(actorId));
        row.setUpdatedByEmail(actorEmail);
        return r2dbcTemplate.insert(row)
                .flatMap(saved -> publishAudit(saved, null, "CREATE", actorId, actorEmail).thenReturn(saved));
    }

    /**
     * Bulk-insert helper used by the CSV upload endpoint. Each row is inserted
     * in its own audit envelope so per-row failures don't roll back accepted
     * rows — the caller aggregates successes and per-row errors.
     */
    public Flux<TenantYieldCurve> addAll(UUID tenantId,
                                         List<AddTenantYieldCurveRequest> rows,
                                         String actorId,
                                         String actorEmail) {
        return Flux.fromIterable(rows)
                .concatMap(req -> add(tenantId, req, actorId, actorEmail));
    }

    @Transactional
    public Mono<TenantYieldCurve> update(UUID tenantId, UUID id,
                                         UpdateTenantYieldCurveRequest req,
                                         String actorId, String actorEmail) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new NoSuchElementException(
                        "Yield curve row not found: " + id)))
                .flatMap(existing -> {
                    if (!existing.getTenantId().equals(tenantId)) {
                        return Mono.<TenantYieldCurve>error(new IllegalArgumentException(
                                "Yield curve row does not belong to tenant"));
                    }
                    TenantYieldCurve snapshot = copy(existing);
                    existing.setSpotRate(req.spotRate());
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
                        "Yield curve row not found: " + id)))
                .flatMap(existing -> {
                    if (!existing.getTenantId().equals(tenantId)) {
                        return Mono.<TenantYieldCurve>error(new IllegalArgumentException(
                                "Yield curve row does not belong to tenant"));
                    }
                    return repository.delete(existing)
                            .then(publishAudit(existing, copy(existing), "DELETE", actorId, actorEmail))
                            .thenReturn(existing);
                })
                .then();
    }

    private static String normaliseSource(String raw) {
        if (raw == null || raw.isBlank()) return "ADMIN";
        String n = raw.trim().toUpperCase();
        if (!ALLOWED_SOURCES.contains(n)) {
            throw new IllegalArgumentException(
                    "source must be one of " + ALLOWED_SOURCES + ", got: " + raw);
        }
        return n;
    }

    private TenantYieldCurve copy(TenantYieldCurve src) {
        TenantYieldCurve c = new TenantYieldCurve();
        c.setId(src.getId());
        c.setTenantId(src.getTenantId());
        c.setCurrency(src.getCurrency());
        c.setTenorMonths(src.getTenorMonths());
        c.setSpotRate(src.getSpotRate());
        c.setSource(src.getSource());
        c.setEffectiveFrom(src.getEffectiveFrom());
        c.setEffectiveTo(src.getEffectiveTo());
        c.setCreatedAt(src.getCreatedAt());
        c.setUpdatedAt(src.getUpdatedAt());
        c.setUpdatedBy(src.getUpdatedBy());
        c.setUpdatedByEmail(src.getUpdatedByEmail());
        return c;
    }

    private Mono<Void> publishAudit(TenantYieldCurve current,
                                    TenantYieldCurve previous,
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
                        String.format("YieldCurve for tenant %s %s@%dm from %s",
                                slug, current.getCurrency(), current.getTenorMonths(),
                                current.getEffectiveFrom()),
                        action,
                        actorId,
                        actorEmail,
                        oldMap,
                        newMap,
                        changed,
                        UUID.randomUUID().toString())));
    }

    private Map<String, Object> toMap(TenantYieldCurve row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("currency", row.getCurrency());
        m.put("tenorMonths", row.getTenorMonths());
        m.put("spotRate", row.getSpotRate() != null ? row.getSpotRate().toPlainString() : null);
        m.put("source", row.getSource());
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

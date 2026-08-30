package com.medfund.tenancy.service;

import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.tenancy.dto.AddTenantMarketDataConfigRequest;
import com.medfund.tenancy.dto.UpdateTenantMarketDataConfigRequest;
import com.medfund.tenancy.entity.Tenant;
import com.medfund.tenancy.entity.TenantMarketDataConfig;
import com.medfund.tenancy.repository.TenantMarketDataConfigRepository;
import com.medfund.tenancy.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * CRUD service for {@code public.tenant_market_data_config} — per-tenant
 * opt-in for yield-curve auto-fetch by the Phase 24 Go market-data-service
 * (Phase 15 §24 / I12 + I16). Mirrors {@link TenantYieldCurveService}
 * shape: list / add / update / delete plus a Rule-2 cross-tenant guard on
 * every mutation.
 *
 * <p>{@code currency} is normalised to upper case, {@code source} is
 * validated against the fixed set kept in sync with V167's CHECK
 * constraint (RBZ_AUTO / SARB_AUTO — no ADMIN source, because ADMIN rows
 * skip auto-fetch entirely and live only in {@code tenant_yield_curve}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantMarketDataConfigService {

    private static final String ENTITY_TYPE = "TENANT_MARKET_DATA_CONFIG";
    private static final Set<String> ALLOWED_SOURCES = Set.of("RBZ_AUTO", "SARB_AUTO");

    private final TenantMarketDataConfigRepository repository;
    private final TenantRepository tenantRepository;
    private final R2dbcEntityTemplate r2dbcTemplate;
    private final AuditPublisher auditPublisher;

    public Flux<TenantMarketDataConfig> list(UUID tenantId) {
        return repository.findByTenantIdOrderByCurrencyAsc(tenantId);
    }

    /**
     * Read path used by the Go market-data-service's cron driver — the
     * daemon polls this every fetch cycle to build its schedule. Returns
     * every enabled row across all tenants; per-tenant filtering happens
     * inside the daemon so a tenant enrolling mid-day picks up on the
     * next tick without a service restart.
     */
    public Flux<TenantMarketDataConfig> listAllEnabled() {
        return repository.findByAutoFetchEnabledTrueOrderByTenantIdAscCurrencyAsc();
    }

    @Transactional
    public Mono<TenantMarketDataConfig> add(UUID tenantId,
                                            AddTenantMarketDataConfigRequest req,
                                            String actorId,
                                            String actorEmail) {
        String source = normaliseSource(req.source());
        String currency = req.currency().trim().toUpperCase();

        TenantMarketDataConfig row = new TenantMarketDataConfig();
        row.setTenantId(tenantId);
        row.setCurrency(currency);
        row.setSource(source);
        row.setAutoFetchEnabled(req.autoFetchEnabled() == null || req.autoFetchEnabled());
        row.setUpdatedBy(parseUuid(actorId));
        row.setUpdatedByEmail(actorEmail);
        return r2dbcTemplate.insert(row)
                .flatMap(saved -> publishAudit(saved, null, "CREATE", actorId, actorEmail)
                        .thenReturn(saved));
    }

    @Transactional
    public Mono<TenantMarketDataConfig> update(UUID tenantId, UUID id,
                                               UpdateTenantMarketDataConfigRequest req,
                                               String actorId, String actorEmail) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new NoSuchElementException(
                        "Market-data config not found: " + id)))
                .flatMap(existing -> {
                    if (!existing.getTenantId().equals(tenantId)) {
                        return Mono.<TenantMarketDataConfig>error(new IllegalArgumentException(
                                "Market-data config does not belong to tenant"));
                    }
                    TenantMarketDataConfig snapshot = copy(existing);
                    if (req.source() != null) {
                        existing.setSource(normaliseSource(req.source()));
                    }
                    if (req.autoFetchEnabled() != null) {
                        existing.setAutoFetchEnabled(req.autoFetchEnabled());
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
                        "Market-data config not found: " + id)))
                .flatMap(existing -> {
                    if (!existing.getTenantId().equals(tenantId)) {
                        return Mono.<TenantMarketDataConfig>error(new IllegalArgumentException(
                                "Market-data config does not belong to tenant"));
                    }
                    return repository.delete(existing)
                            .then(publishAudit(existing, copy(existing), "DELETE", actorId, actorEmail))
                            .thenReturn(existing);
                })
                .then();
    }

    private static String normaliseSource(String raw) {
        if (raw == null) throw new IllegalArgumentException("source is required");
        String n = raw.trim().toUpperCase();
        if (!ALLOWED_SOURCES.contains(n)) {
            throw new IllegalArgumentException(
                    "source must be one of " + ALLOWED_SOURCES + ", got: " + raw);
        }
        return n;
    }

    private TenantMarketDataConfig copy(TenantMarketDataConfig src) {
        TenantMarketDataConfig c = new TenantMarketDataConfig();
        c.setId(src.getId());
        c.setTenantId(src.getTenantId());
        c.setCurrency(src.getCurrency());
        c.setSource(src.getSource());
        c.setAutoFetchEnabled(src.getAutoFetchEnabled());
        c.setCreatedAt(src.getCreatedAt());
        c.setUpdatedAt(src.getUpdatedAt());
        c.setUpdatedBy(src.getUpdatedBy());
        c.setUpdatedByEmail(src.getUpdatedByEmail());
        return c;
    }

    private Mono<Void> publishAudit(TenantMarketDataConfig current,
                                    TenantMarketDataConfig previous,
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
                        String.format("MarketDataConfig for tenant %s %s → %s",
                                slug, current.getCurrency(), current.getSource()),
                        action,
                        actorId,
                        actorEmail,
                        oldMap,
                        newMap,
                        changed,
                        UUID.randomUUID().toString())));
    }

    private Map<String, Object> toMap(TenantMarketDataConfig row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("currency", row.getCurrency());
        m.put("source", row.getSource());
        m.put("autoFetchEnabled", row.getAutoFetchEnabled());
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

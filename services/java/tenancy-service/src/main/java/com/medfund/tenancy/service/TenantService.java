package com.medfund.tenancy.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.scheduler.ScheduledJobService;
import com.medfund.tenancy.dto.CreateTenantRequest;
import com.medfund.tenancy.dto.TenantPage;
import com.medfund.tenancy.dto.TenantQueryParams;
import com.medfund.tenancy.dto.TenantResponse;
import com.medfund.tenancy.dto.UpdateTenantRequest;
import com.medfund.tenancy.entity.Tenant;
import com.medfund.tenancy.entity.TenantCurrencyConfig;
import com.medfund.tenancy.exception.TenantNotFoundException;
import com.medfund.tenancy.exception.TenantSlugConflictException;
import com.medfund.tenancy.repository.CurrencyRepository;
import com.medfund.tenancy.repository.TenantCurrencyConfigRepository;
import com.medfund.tenancy.repository.TenantRepository;
import com.medfund.tenancy.util.JsonString;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TenantService {

    private static final Duration CACHE_TTL = Duration.ofMinutes(2);

    private final TenantRepository tenantRepository;
    private final TenantCurrencyConfigRepository currencyConfigRepository;
    private final CurrencyRepository currencyRepository;
    private final SchemaProvisioningService schemaProvisioning;
    private final KeycloakRealmService keycloakRealmService;
    private final AuditPublisher auditPublisher;
    private final TenantEventPublisher eventPublisher;
    private final ReactiveStringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final R2dbcEntityTemplate r2dbcTemplate;
    private final ScheduledJobService scheduledJobService;

    // ── Search (paginated, cached) ─────────────────────────────────────────────

    public Mono<TenantPage> search(TenantQueryParams params) {
        String key = cacheKey(params);
        return redis.opsForValue().get(key)
                .flatMap(this::deserializePage)
                .switchIfEmpty(searchFromDb(params)
                        .flatMap(page -> serializeAndCache(key, page)));
    }

    private Mono<TenantPage> searchFromDb(TenantQueryParams params) {
        Mono<List<Tenant>> rowsMono = tenantRepository.search(params).collectList();
        Mono<Long> countMono = tenantRepository.countSearch(params);

        return Mono.zip(rowsMono, countMono).map(tuple -> {
            List<Tenant> rows = tuple.getT1();
            long total = tuple.getT2();
            int totalPages = (int) Math.ceil((double) total / params.size());
            return new TenantPage(
                    rows.stream().map(TenantResponse::from).toList(),
                    total,
                    Math.max(totalPages, 1),
                    params.page(),
                    params.size());
        });
    }

    // ── Lookups ───────────────────────────────────────────────────────────────

    public Flux<Tenant> findAll() {
        return tenantRepository.findAllOrderByCreatedAtDesc();
    }

    public Mono<Tenant> findById(UUID id) {
        return tenantRepository.findById(id)
                .switchIfEmpty(Mono.error(new TenantNotFoundException(id)));
    }

    public Mono<Tenant> findBySlug(String slug) {
        return tenantRepository.findBySlug(slug)
                .switchIfEmpty(Mono.error(new TenantNotFoundException(slug)));
    }

    public Mono<Tenant> findByDomain(String domain) {
        return tenantRepository.findByDomain(domain);
    }

    public Flux<Tenant> findByStatus(String status) {
        return tenantRepository.findByStatus(status);
    }

    // ── Writes ────────────────────────────────────────────────────────────────

    @Transactional
    public Mono<Tenant> create(CreateTenantRequest request, String actorId, String actorEmail) {
        String defaultCurrency = request.defaultCurrencyCodeOrDefault();
        return currencyRepository.existsActiveByCode(defaultCurrency)
                .flatMap(currencyExists -> {
                    if (Boolean.FALSE.equals(currencyExists)) {
                        return Mono.<Boolean>error(new IllegalArgumentException(
                                "defaultCurrencyCode '" + defaultCurrency
                                        + "' is not in the active master registry"));
                    }
                    return tenantRepository.existsBySlug(request.slug());
                })
                .flatMap(exists -> {
                    if (Boolean.TRUE.equals(exists)) {
                        return Mono.<Tenant>error(new TenantSlugConflictException(request.slug()));
                    }

                    // Schema name derived from slug — unique and readable.
                    // e.g. slug "health-first" → schema "tenant_health_first"
                    String schemaName = "tenant_" + request.slug().replace("-", "_");
                    String realmName  = "medfund-" + request.slug();

                    Tenant tenant = new Tenant();
                    // id NOT set — PostgreSQL generates it via DEFAULT gen_random_uuid()
                    tenant.setName(request.name());
                    tenant.setSlug(request.slug());
                    tenant.setDomain(request.domain());
                    tenant.setSchemaName(schemaName);
                    tenant.setPlanId(request.planId());
                    tenant.setStatus("active");
                    tenant.setSettings(JsonString.of(request.settingsOrDefault()));
                    tenant.setBranding(JsonString.empty());
                    tenant.setContactEmail(request.contactEmail());
                    tenant.setCountryCode(request.countryCode());
                    tenant.setTimezone(request.timezoneOrDefault());
                    tenant.setMembershipModel(request.membershipModelOrDefault());
                    tenant.setKeycloakRealm(realmName);

                    // Use r2dbcTemplate.insert() — always executes INSERT regardless of id state,
                    // avoiding the repository.save() INSERT/UPDATE ambiguity.
                    return r2dbcTemplate.insert(tenant)
                            .flatMap(saved -> schemaProvisioning.provisionSchema(schemaName)
                                    .then(keycloakRealmService.createRealm(realmName, saved))
                                    .then(createDefaultCurrencyConfig(saved.getId(), request.defaultCurrencyCodeOrDefault()))
                                    // Seed the 6 default scheduled jobs (BILLING_CYCLE,
                                    // OVERDUE_CHECK, PAYMENT_RUN, AGE_PROCESSING,
                                    // PRE_AUTH_EXPIRY, TARIFF_ACTIVATION) bound to this
                                    // tenant. Without this hook the JobDispatcher has
                                    // nothing to run for the tenant until a platform
                                    // admin manually re-seeds.
                                    .then(scheduledJobService.seedDefaults(saved.getId(), actorId))
                                    .then(publishAuditEvent(saved, null, actorId, actorEmail, "CREATE"))
                                    .then(eventPublisher.publishTenantProvisioned(saved))
                                    .thenReturn(saved))
                            .flatMap(saved -> evictCaches().thenReturn(saved));
                });
    }

    @Transactional
    public Mono<Tenant> update(UUID id, UpdateTenantRequest request, String actorId, String actorEmail) {
        return tenantRepository.findById(id)
                .switchIfEmpty(Mono.error(new TenantNotFoundException(id)))
                .flatMap(existing -> {
                    Tenant old = copyTenant(existing);

                    if (request.name() != null)            existing.setName(request.name());
                    if (request.domain() != null)          existing.setDomain(request.domain());
                    if (request.planId() != null)          existing.setPlanId(request.planId());
                    if (request.contactEmail() != null)    existing.setContactEmail(request.contactEmail());
                    if (request.timezone() != null)        existing.setTimezone(request.timezone());
                    if (request.membershipModel() != null) existing.setMembershipModel(request.membershipModel());
                    if (request.pricingModel() != null)    existing.setPricingModel(request.pricingModel());
                    if (request.memberNumberScheme() != null) existing.setMemberNumberScheme(request.memberNumberScheme());
                    if (request.settings() != null)        existing.setSettings(JsonString.of(request.settings()));
                    if (request.branding() != null)        existing.setBranding(JsonString.of(request.branding()));
                    existing.setUpdatedAt(Instant.now());

                    return tenantRepository.save(existing)
                            .flatMap(saved -> publishAuditEvent(saved, old, actorId, actorEmail, "UPDATE")
                                    .thenReturn(saved))
                            .flatMap(saved -> evictCaches().thenReturn(saved));
                });
    }

    @Transactional
    public Mono<Tenant> suspend(UUID id, String actorId, String actorEmail) {
        return tenantRepository.findById(id)
                .switchIfEmpty(Mono.error(new TenantNotFoundException(id)))
                .flatMap(tenant -> {
                    Tenant old = copyTenant(tenant);
                    tenant.setStatus("suspended");
                    tenant.setUpdatedAt(Instant.now());
                    return tenantRepository.save(tenant)
                            .flatMap(saved -> publishAuditEvent(saved, old, actorId, actorEmail, "UPDATE")
                                    .then(eventPublisher.publishTenantSuspended(saved))
                                    .thenReturn(saved))
                            .flatMap(saved -> evictCaches().thenReturn(saved));
                });
    }

    @Transactional
    public Mono<Tenant> activate(UUID id, String actorId, String actorEmail) {
        return tenantRepository.findById(id)
                .switchIfEmpty(Mono.error(new TenantNotFoundException(id)))
                .flatMap(tenant -> {
                    Tenant old = copyTenant(tenant);
                    tenant.setStatus("active");
                    tenant.setUpdatedAt(Instant.now());
                    return tenantRepository.save(tenant)
                            .flatMap(saved -> publishAuditEvent(saved, old, actorId, actorEmail, "UPDATE")
                                    .thenReturn(saved))
                            .flatMap(saved -> evictCaches().thenReturn(saved));
                });
    }

    // ── Cache helpers ─────────────────────────────────────────────────────────

    private String cacheKey(TenantQueryParams p) {
        return String.format("tenants:%s:%s:%s:%s:%d:%d",
                p.q()              != null ? p.q()              : "_",
                p.status()         != null ? p.status()         : "_",
                p.membershipModel() != null ? p.membershipModel() : "_",
                p.countryCode()    != null ? p.countryCode()    : "_",
                p.page(), p.size());
    }

    private Mono<TenantPage> deserializePage(String json) {
        try {
            return Mono.just(objectMapper.readValue(json, TenantPage.class));
        } catch (JsonProcessingException e) {
            return Mono.empty();
        }
    }

    private Mono<TenantPage> serializeAndCache(String key, TenantPage page) {
        try {
            String json = objectMapper.writeValueAsString(page);
            return redis.opsForValue().set(key, json, CACHE_TTL).thenReturn(page);
        } catch (JsonProcessingException e) {
            return Mono.just(page);
        }
    }

    private Mono<Void> evictCaches() {
        return redis.keys("tenants:*")
                .collectList()
                .flatMap(keys -> {
                    if (keys.isEmpty()) return Mono.just(0L);
                    return redis.delete(keys.toArray(String[]::new));
                })
                .then();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private Mono<Void> createDefaultCurrencyConfig(UUID tenantId, String currencyCode) {
        TenantCurrencyConfig config = new TenantCurrencyConfig();
        // id NOT set — PostgreSQL generates it via DEFAULT gen_random_uuid()
        config.setTenantId(tenantId);
        config.setCurrencyCode(currencyCode);
        config.setIsDefault(true);
        config.setIsActive(true);
        config.setIsBillingCurrency(true);
        config.setIsClaimsCurrency(true);
        config.setIsPaymentCurrency(true);
        config.setExchangeRateSource("manual");
        return r2dbcTemplate.insert(config).then();
    }

    private Mono<Void> publishAuditEvent(Tenant current, Tenant previous,
                                          String actorId, String actorEmail, String action) {
        Map<String, Object> oldMap = previous != null ? tenantToMap(previous) : null;
        Map<String, Object> newMap = tenantToMap(current);
        String[] changedFields = "UPDATE".equals(action) && oldMap != null
                ? computeChangedFields(oldMap, newMap) : null;

        var event = AuditEvent.create(
                "platform",
                "TENANT",
                current.getId().toString(),
                current.getName(),
                action,
                actorId,
                actorEmail,
                oldMap,
                newMap,
                changedFields,
                UUID.randomUUID().toString()
        );
        return auditPublisher.publish(event);
    }

    private Map<String, Object> tenantToMap(Tenant t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", t.getName());
        m.put("slug", t.getSlug());
        m.put("domain", t.getDomain());
        m.put("status", t.getStatus());
        m.put("contactEmail", t.getContactEmail());
        m.put("countryCode", t.getCountryCode());
        m.put("timezone", t.getTimezone());
        m.put("membershipModel", t.getMembershipModel());
        m.put("planId", t.getPlanId() != null ? t.getPlanId().toString() : null);
        m.put("schemaName", t.getSchemaName());
        m.put("keycloakRealm", t.getKeycloakRealm());
        m.put("settings", t.getSettings() != null ? t.getSettings().value() : null);
        m.put("branding", t.getBranding() != null ? t.getBranding().value() : null);
        m.put("createdAt", t.getCreatedAt() != null ? t.getCreatedAt().toString() : null);
        m.put("updatedAt", t.getUpdatedAt() != null ? t.getUpdatedAt().toString() : null);
        return m;
    }

    private String[] computeChangedFields(Map<String, Object> oldMap, Map<String, Object> newMap) {
        List<String> changed = new ArrayList<>();
        for (String key : newMap.keySet()) {
            if (!Objects.equals(oldMap.get(key), newMap.get(key))) {
                changed.add(key);
            }
        }
        return changed.toArray(String[]::new);
    }

    private Tenant copyTenant(Tenant source) {
        Tenant copy = new Tenant();
        copy.setId(source.getId());
        copy.setName(source.getName());
        copy.setSlug(source.getSlug());
        copy.setDomain(source.getDomain());
        copy.setSchemaName(source.getSchemaName());
        copy.setPlanId(source.getPlanId());
        copy.setStatus(source.getStatus());
        copy.setSettings(source.getSettings());
        copy.setBranding(source.getBranding());
        copy.setContactEmail(source.getContactEmail());
        copy.setCountryCode(source.getCountryCode());
        copy.setTimezone(source.getTimezone());
        copy.setMembershipModel(source.getMembershipModel());
        copy.setKeycloakRealm(source.getKeycloakRealm());
        copy.setCreatedAt(source.getCreatedAt());
        copy.setUpdatedAt(source.getUpdatedAt());
        return copy;
    }
}

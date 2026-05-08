package com.medfund.tenancy.service;

import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.tenancy.entity.TenantCurrencyConfig;
import com.medfund.tenancy.exception.CurrencyConflictException;
import com.medfund.tenancy.repository.CurrencyRepository;
import com.medfund.tenancy.repository.TenantCurrencyConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TenantCurrencyService {

    private final TenantCurrencyConfigRepository repository;
    private final CurrencyRepository currencyRepository;
    private final R2dbcEntityTemplate r2dbcTemplate;
    private final AuditPublisher auditPublisher;
    private final CurrencyEventPublisher eventPublisher;

    // ── Reads ─────────────────────────────────────────────────────────────────

    public Flux<TenantCurrencyConfig> listForTenant(UUID tenantId) {
        return repository.findByTenantId(tenantId);
    }

    public Mono<TenantCurrencyConfig> findDefault(UUID tenantId) {
        return repository.findDefaultByTenantId(tenantId);
    }

    public Mono<Boolean> isAllowed(UUID tenantId, String currencyCode) {
        return repository.existsActiveByTenantIdAndCurrencyCode(tenantId, currencyCode);
    }

    // ── Writes ───────────────────────────────────────────────────────────────

    @Transactional
    public Mono<TenantCurrencyConfig> add(UUID tenantId, AddCurrencyRequest request,
                                          String actorId, String actorEmail) {
        return currencyRepository.existsActiveByCode(request.currencyCode())
                .flatMap(exists -> {
                    if (Boolean.FALSE.equals(exists)) {
                        return Mono.<TenantCurrencyConfig>error(new IllegalArgumentException(
                                "Currency " + request.currencyCode() + " is not in the master registry"));
                    }
                    return repository.findByTenantIdAndCurrencyCode(tenantId, request.currencyCode())
                            .flatMap(existing -> Mono.<TenantCurrencyConfig>error(new CurrencyConflictException(
                                    "Currency " + request.currencyCode() + " is already configured for this tenant")))
                            .switchIfEmpty(Mono.defer(() -> insert(tenantId, request, actorId, actorEmail)));
                });
    }

    private Mono<TenantCurrencyConfig> insert(UUID tenantId, AddCurrencyRequest request,
                                              String actorId, String actorEmail) {
        TenantCurrencyConfig config = new TenantCurrencyConfig();
        config.setTenantId(tenantId);
        config.setCurrencyCode(request.currencyCode());
        config.setIsDefault(Boolean.TRUE.equals(request.isDefault()));
        config.setIsActive(true);
        config.setIsBillingCurrency(Boolean.TRUE.equals(request.isBillingCurrency()));
        config.setIsClaimsCurrency(Boolean.TRUE.equals(request.isClaimsCurrency()));
        config.setIsPaymentCurrency(Boolean.TRUE.equals(request.isPaymentCurrency()));
        config.setExchangeRateSource(request.exchangeRateSource() != null ? request.exchangeRateSource() : "manual");

        Mono<Void> demotePrior = Boolean.TRUE.equals(request.isDefault())
                ? clearDefaultFlag(tenantId)
                : Mono.empty();

        return demotePrior
                .then(r2dbcTemplate.insert(config))
                .flatMap(saved -> publishAudit(saved, null, "CREATE", actorId, actorEmail).thenReturn(saved))
                .flatMap(saved -> eventPublisher.publishTenantCurrencyUpdated(saved, "CREATED").thenReturn(saved));
    }

    @Transactional
    public Mono<TenantCurrencyConfig> update(UUID tenantId, UUID configId, UpdateCurrencyRequest request,
                                             String actorId, String actorEmail) {
        return repository.findById(configId)
                .switchIfEmpty(Mono.error(new NoSuchElementException("Tenant currency config not found")))
                .flatMap(existing -> {
                    if (!existing.getTenantId().equals(tenantId)) {
                        return Mono.<TenantCurrencyConfig>error(new IllegalArgumentException(
                                "Currency config does not belong to tenant"));
                    }
                    TenantCurrencyConfig snapshot = copy(existing);

                    if (request.isActive() != null)            existing.setIsActive(request.isActive());
                    if (request.isBillingCurrency() != null)   existing.setIsBillingCurrency(request.isBillingCurrency());
                    if (request.isClaimsCurrency() != null)    existing.setIsClaimsCurrency(request.isClaimsCurrency());
                    if (request.isPaymentCurrency() != null)   existing.setIsPaymentCurrency(request.isPaymentCurrency());
                    if (request.exchangeRateSource() != null)  existing.setExchangeRateSource(request.exchangeRateSource());

                    Mono<Void> handleDefault = Boolean.TRUE.equals(request.isDefault()) && !Boolean.TRUE.equals(existing.getIsDefault())
                            ? clearDefaultFlag(tenantId).doOnSuccess(v -> existing.setIsDefault(true))
                            : Mono.empty();

                    return handleDefault
                            .then(repository.save(existing))
                            .flatMap(saved -> publishAudit(saved, snapshot, "UPDATE", actorId, actorEmail).thenReturn(saved))
                            .flatMap(saved -> eventPublisher.publishTenantCurrencyUpdated(saved, "UPDATED").thenReturn(saved));
                });
    }

    @Transactional
    public Mono<Void> remove(UUID tenantId, UUID configId, String actorId, String actorEmail) {
        return repository.findById(configId)
                .switchIfEmpty(Mono.error(new NoSuchElementException("Tenant currency config not found")))
                .flatMap(existing -> {
                    if (!existing.getTenantId().equals(tenantId)) {
                        return Mono.<TenantCurrencyConfig>error(new IllegalArgumentException(
                                "Currency config does not belong to tenant"));
                    }
                    if (Boolean.TRUE.equals(existing.getIsDefault())) {
                        return Mono.<TenantCurrencyConfig>error(new CurrencyConflictException(
                                "Cannot remove the tenant's default currency. Promote another currency first."));
                    }
                    return repository.delete(existing)
                            .then(publishAudit(existing, copy(existing), "DELETE", actorId, actorEmail))
                            .then(eventPublisher.publishTenantCurrencyUpdated(existing, "DELETED"))
                            .thenReturn(existing);
                })
                .then();
    }

    private Mono<Void> clearDefaultFlag(UUID tenantId) {
        return repository.findDefaultByTenantId(tenantId)
                .flatMap(prev -> {
                    prev.setIsDefault(false);
                    return repository.save(prev);
                })
                .then();
    }

    private TenantCurrencyConfig copy(TenantCurrencyConfig src) {
        TenantCurrencyConfig c = new TenantCurrencyConfig();
        c.setId(src.getId());
        c.setTenantId(src.getTenantId());
        c.setCurrencyCode(src.getCurrencyCode());
        c.setIsDefault(src.getIsDefault());
        c.setIsActive(src.getIsActive());
        c.setIsBillingCurrency(src.getIsBillingCurrency());
        c.setIsClaimsCurrency(src.getIsClaimsCurrency());
        c.setIsPaymentCurrency(src.getIsPaymentCurrency());
        c.setExchangeRateSource(src.getExchangeRateSource());
        return c;
    }

    private Mono<Void> publishAudit(TenantCurrencyConfig current, TenantCurrencyConfig previous,
                                    String action, String actorId, String actorEmail) {
        Map<String, Object> oldMap = previous != null ? toMap(previous) : null;
        Map<String, Object> newMap = "DELETE".equals(action) ? null : toMap(current);
        String[] changedFields = "UPDATE".equals(action) && oldMap != null
                ? changedFields(oldMap, toMap(current)) : null;

        var event = AuditEvent.create(
                current.getTenantId().toString(),
                "TENANT_CURRENCY_CONFIG",
                current.getId().toString(),
                current.getCurrencyCode(),
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

    private Map<String, Object> toMap(TenantCurrencyConfig c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("currencyCode", c.getCurrencyCode());
        m.put("isDefault", c.getIsDefault());
        m.put("isActive", c.getIsActive());
        m.put("isBillingCurrency", c.getIsBillingCurrency());
        m.put("isClaimsCurrency", c.getIsClaimsCurrency());
        m.put("isPaymentCurrency", c.getIsPaymentCurrency());
        m.put("exchangeRateSource", c.getExchangeRateSource());
        return m;
    }

    private String[] changedFields(Map<String, Object> oldMap, Map<String, Object> newMap) {
        return newMap.keySet().stream()
                .filter(k -> !Objects.equals(oldMap.get(k), newMap.get(k)))
                .toArray(String[]::new);
    }

    public record AddCurrencyRequest(
            String currencyCode,
            Boolean isDefault,
            Boolean isBillingCurrency,
            Boolean isClaimsCurrency,
            Boolean isPaymentCurrency,
            String exchangeRateSource
    ) {}

    public record UpdateCurrencyRequest(
            Boolean isDefault,
            Boolean isActive,
            Boolean isBillingCurrency,
            Boolean isClaimsCurrency,
            Boolean isPaymentCurrency,
            String exchangeRateSource
    ) {}
}

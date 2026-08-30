package com.medfund.tenancy.repository;

import com.medfund.tenancy.entity.TenantMarketDataConfig;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface TenantMarketDataConfigRepository
        extends ReactiveCrudRepository<TenantMarketDataConfig, UUID> {

    Flux<TenantMarketDataConfig> findByTenantIdOrderByCurrencyAsc(UUID tenantId);

    Mono<TenantMarketDataConfig> findByTenantIdAndCurrency(UUID tenantId, String currency);

    /**
     * Read path used by the market-data-service Go daemon to build its
     * per-tenant fetch schedule. Returns only rows currently enabled for
     * auto-fetch across all tenants.
     */
    Flux<TenantMarketDataConfig> findByAutoFetchEnabledTrueOrderByTenantIdAscCurrencyAsc();
}

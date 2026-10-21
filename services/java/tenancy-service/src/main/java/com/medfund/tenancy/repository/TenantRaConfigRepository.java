package com.medfund.tenancy.repository;

import com.medfund.tenancy.entity.TenantRaConfig;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface TenantRaConfigRepository
        extends ReactiveCrudRepository<TenantRaConfig, UUID> {

    Flux<TenantRaConfig> findByTenantIdOrderByPortfolioIdAscEffectiveFromDesc(UUID tenantId);
}

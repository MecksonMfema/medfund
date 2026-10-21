package com.medfund.tenancy.repository;

import com.medfund.tenancy.entity.TenantHighCostClaimantConfig;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface TenantHighCostClaimantConfigRepository
        extends ReactiveCrudRepository<TenantHighCostClaimantConfig, UUID> {

    Mono<TenantHighCostClaimantConfig> findByTenantId(UUID tenantId);
}

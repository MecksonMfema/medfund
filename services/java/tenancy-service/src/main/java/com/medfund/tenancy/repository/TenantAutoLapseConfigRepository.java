package com.medfund.tenancy.repository;

import com.medfund.tenancy.entity.TenantAutoLapseConfig;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface TenantAutoLapseConfigRepository
        extends ReactiveCrudRepository<TenantAutoLapseConfig, UUID> {

    Mono<TenantAutoLapseConfig> findByTenantId(UUID tenantId);
}

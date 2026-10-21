package com.medfund.tenancy.repository;

import com.medfund.tenancy.entity.TenantEndorsementConfig;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface TenantEndorsementConfigRepository
        extends ReactiveCrudRepository<TenantEndorsementConfig, UUID> {

    Mono<TenantEndorsementConfig> findByTenantId(UUID tenantId);
}

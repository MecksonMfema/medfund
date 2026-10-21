package com.medfund.tenancy.repository;

import com.medfund.tenancy.entity.UsTenantNaicConfig;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface UsTenantNaicConfigRepository
        extends ReactiveCrudRepository<UsTenantNaicConfig, UUID> {

    Flux<UsTenantNaicConfig> findByTenantIdOrderByEffectiveFromDesc(UUID tenantId);
}

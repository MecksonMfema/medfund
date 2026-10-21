package com.medfund.finance.regulatory.aml.repository;

import com.medfund.finance.regulatory.aml.entity.TenantAmlThresholdConfig;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface TenantAmlThresholdConfigRepository
        extends ReactiveCrudRepository<TenantAmlThresholdConfig, UUID> {

    Flux<TenantAmlThresholdConfig> findByTenantIdOrderByEffectiveFromDesc(UUID tenantId);
}

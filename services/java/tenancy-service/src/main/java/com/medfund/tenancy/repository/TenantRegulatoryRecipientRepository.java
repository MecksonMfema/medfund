package com.medfund.tenancy.repository;

import com.medfund.tenancy.entity.TenantRegulatoryRecipient;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface TenantRegulatoryRecipientRepository
        extends ReactiveCrudRepository<TenantRegulatoryRecipient, UUID> {

    Flux<TenantRegulatoryRecipient> findByTenantIdOrderByEmailAsc(UUID tenantId);

    Flux<TenantRegulatoryRecipient> findByTenantIdAndIsActiveTrue(UUID tenantId);
}

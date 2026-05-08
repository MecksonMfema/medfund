package com.medfund.tenancy.repository;

import com.medfund.tenancy.entity.TenantEmailTemplate;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface TenantEmailTemplateRepository extends ReactiveCrudRepository<TenantEmailTemplate, UUID> {

    Flux<TenantEmailTemplate> findByTenantId(UUID tenantId);

    Mono<TenantEmailTemplate> findByTenantIdAndTemplateKey(UUID tenantId, String templateKey);

    Mono<Void> deleteByTenantIdAndTemplateKey(UUID tenantId, String templateKey);
}

package com.medfund.tenancy.repository;

import com.medfund.tenancy.entity.TenantMortalityBasis;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.UUID;

public interface TenantMortalityBasisRepository
        extends ReactiveCrudRepository<TenantMortalityBasis, UUID> {

    Flux<TenantMortalityBasis> findByTenantIdOrderByInsuranceLineAscEffectiveFromDesc(UUID tenantId);

    Mono<TenantMortalityBasis> findByTenantIdAndInsuranceLineAndEffectiveFrom(
            UUID tenantId, String insuranceLine, LocalDate effectiveFrom);
}

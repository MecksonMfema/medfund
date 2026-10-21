package com.medfund.tenancy.repository;

import com.medfund.tenancy.entity.TenantMorbidityBasis;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.UUID;

public interface TenantMorbidityBasisRepository
        extends ReactiveCrudRepository<TenantMorbidityBasis, UUID> {

    Flux<TenantMorbidityBasis> findByTenantIdOrderByInsuranceLineAscEffectiveFromDesc(UUID tenantId);

    Mono<TenantMorbidityBasis> findByTenantIdAndInsuranceLineAndEffectiveFrom(
            UUID tenantId, String insuranceLine, LocalDate effectiveFrom);
}

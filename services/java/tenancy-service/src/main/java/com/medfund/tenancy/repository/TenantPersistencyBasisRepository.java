package com.medfund.tenancy.repository;

import com.medfund.tenancy.entity.TenantPersistencyBasis;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.UUID;

public interface TenantPersistencyBasisRepository
        extends ReactiveCrudRepository<TenantPersistencyBasis, UUID> {

    Flux<TenantPersistencyBasis> findByTenantIdOrderByInsuranceLineAscCohortMonthsAsc(UUID tenantId);

    Mono<TenantPersistencyBasis> findByTenantIdAndInsuranceLineAndCohortMonthsAndEffectiveFrom(
            UUID tenantId, String insuranceLine, Integer cohortMonths, LocalDate effectiveFrom);
}

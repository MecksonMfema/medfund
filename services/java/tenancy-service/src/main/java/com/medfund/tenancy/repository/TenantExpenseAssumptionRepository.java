package com.medfund.tenancy.repository;

import com.medfund.tenancy.entity.TenantExpenseAssumption;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface TenantExpenseAssumptionRepository
        extends ReactiveCrudRepository<TenantExpenseAssumption, UUID> {

    Flux<TenantExpenseAssumption> findByTenantIdOrderByInsuranceLineAscExpenseTypeAscEffectiveFromDesc(
            UUID tenantId);
}

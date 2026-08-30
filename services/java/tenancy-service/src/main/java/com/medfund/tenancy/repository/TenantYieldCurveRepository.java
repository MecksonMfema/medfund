package com.medfund.tenancy.repository;

import com.medfund.tenancy.entity.TenantYieldCurve;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface TenantYieldCurveRepository
        extends ReactiveCrudRepository<TenantYieldCurve, UUID> {

    Flux<TenantYieldCurve> findByTenantIdOrderByCurrencyAscEffectiveFromDescTenorMonthsAsc(UUID tenantId);

    Flux<TenantYieldCurve> findByTenantIdAndCurrencyOrderByEffectiveFromDescTenorMonthsAsc(
            UUID tenantId, String currency);
}

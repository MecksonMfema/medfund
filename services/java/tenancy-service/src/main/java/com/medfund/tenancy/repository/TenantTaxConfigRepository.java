package com.medfund.tenancy.repository;

import com.medfund.tenancy.entity.TenantTaxConfig;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface TenantTaxConfigRepository
        extends ReactiveCrudRepository<TenantTaxConfig, UUID> {

    /**
     * All rows for a tenant, most-recent-effective-first. Consumed by the
     * admin list endpoint and by finance-service tax shapers via
     * cross-service read (Phase 20 / 21).
     */
    Flux<TenantTaxConfig> findByTenantIdOrderByEffectiveFromDesc(UUID tenantId);

    /**
     * Narrower list — filtered by tax_type so the admin UI can render
     * a VAT-only or WITHHOLDING-only tab without pulling both.
     */
    Flux<TenantTaxConfig> findByTenantIdAndTaxTypeOrderByEffectiveFromDesc(
            UUID tenantId, String taxType);
}

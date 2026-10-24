package com.medfund.tenancy.repository;

import com.medfund.tenancy.entity.TenantModuleEntitlement;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface TenantModuleEntitlementRepository
        extends R2dbcRepository<TenantModuleEntitlement, UUID> {

    @Query("""
            SELECT * FROM public.tenant_module_entitlements
             ORDER BY tenant_id, module_key
            """)
    Flux<TenantModuleEntitlement> findAllOrdered();

    Flux<TenantModuleEntitlement> findByTenantId(UUID tenantId);

    @Query("""
            SELECT * FROM public.tenant_module_entitlements
             WHERE tenant_id = :tenantId AND module_key = :moduleKey
            """)
    Mono<TenantModuleEntitlement> findByTenantIdAndModuleKey(UUID tenantId, String moduleKey);
}

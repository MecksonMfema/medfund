package com.medfund.tenancy.repository;

import com.medfund.tenancy.entity.TenantCurrencyConfig;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface TenantCurrencyConfigRepository extends R2dbcRepository<TenantCurrencyConfig, UUID> {

    @Query("SELECT * FROM public.tenant_currency_config WHERE tenant_id = :tenantId AND is_active = true")
    Flux<TenantCurrencyConfig> findByTenantId(UUID tenantId);

    @Query("SELECT * FROM public.tenant_currency_config WHERE tenant_id = :tenantId AND is_default = true")
    Mono<TenantCurrencyConfig> findDefaultByTenantId(UUID tenantId);

    @Query("SELECT * FROM public.tenant_currency_config WHERE tenant_id = :tenantId AND currency_code = :code")
    Mono<TenantCurrencyConfig> findByTenantIdAndCurrencyCode(UUID tenantId, String code);

    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM public.tenant_currency_config
             WHERE tenant_id = :tenantId
               AND currency_code = :code
               AND is_active = true
        )
        """)
    Mono<Boolean> existsActiveByTenantIdAndCurrencyCode(UUID tenantId, String code);
}

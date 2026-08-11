package com.medfund.tenancy.repository;

import com.medfund.tenancy.entity.TenantReportConfig;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface TenantReportConfigRepository extends R2dbcRepository<TenantReportConfig, UUID> {

    Flux<TenantReportConfig> findByTenantId(UUID tenantId);

    @Query("""
            SELECT * FROM public.tenant_report_config
             WHERE tenant_id = :tenantId AND report_key = :reportKey
            """)
    Mono<TenantReportConfig> findByTenantIdAndReportKey(UUID tenantId, String reportKey);
}

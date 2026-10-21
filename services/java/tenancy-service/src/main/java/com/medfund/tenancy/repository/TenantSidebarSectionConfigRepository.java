package com.medfund.tenancy.repository;

import com.medfund.tenancy.entity.TenantSidebarSectionConfig;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface TenantSidebarSectionConfigRepository
        extends R2dbcRepository<TenantSidebarSectionConfig, UUID> {

    Flux<TenantSidebarSectionConfig> findByTenantId(UUID tenantId);

    @Query("""
            SELECT * FROM public.tenant_sidebar_section_config
             WHERE tenant_id = :tenantId AND section_key = :sectionKey
            """)
    Mono<TenantSidebarSectionConfig> findByTenantIdAndSectionKey(UUID tenantId, String sectionKey);
}

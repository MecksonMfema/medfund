package com.medfund.tenancy.repository;

import com.medfund.tenancy.entity.TenantRegulatoryTemplate;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.UUID;

public interface TenantRegulatoryTemplateRepository
        extends ReactiveCrudRepository<TenantRegulatoryTemplate, UUID> {

    /** List every override for a tenant, newest-first per (regulator, report_key). */
    Flux<TenantRegulatoryTemplate> findByTenantIdOrderByRegulatorAscReportKeyAscEffectiveFromDesc(UUID tenantId);

    /**
     * Highest effective row ≤ {@code effectiveDate} for a given
     * (tenant, regulator, report_key). Ties on effective_from break by
     * newest {@code uploaded_at} — a rare edge case but a same-day re-upload
     * should be picked up.
     */
    @Query("""
            SELECT * FROM public.tenant_regulatory_template
             WHERE tenant_id = :tenantId
               AND regulator = :regulator
               AND report_key = :reportKey
               AND effective_from <= :effectiveDate
             ORDER BY effective_from DESC, uploaded_at DESC
             LIMIT 1
            """)
    Mono<TenantRegulatoryTemplate> findEffective(UUID tenantId, String regulator, String reportKey, LocalDate effectiveDate);

    /** Guard against duplicate uploads for the same (tenant, regulator, key, effective_from). */
    Mono<Boolean> existsByTenantIdAndRegulatorAndReportKeyAndEffectiveFrom(
            UUID tenantId, String regulator, String reportKey, LocalDate effectiveFrom);
}

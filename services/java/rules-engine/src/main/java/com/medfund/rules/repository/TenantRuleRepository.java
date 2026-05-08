package com.medfund.rules.repository;

import com.medfund.rules.entity.TenantRule;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface TenantRuleRepository extends R2dbcRepository<TenantRule, UUID> {

    @Query("SELECT * FROM public.tenant_rules WHERE tenant_id = :tenantId ORDER BY priority DESC, created_at DESC")
    Flux<TenantRule> findByTenant(UUID tenantId);

    @Query("SELECT * FROM public.tenant_rules WHERE tenant_id = :tenantId AND category = :category ORDER BY priority DESC, created_at DESC")
    Flux<TenantRule> findByTenantAndCategory(UUID tenantId, String category);

    @Query("SELECT * FROM public.tenant_rules WHERE tenant_id = :tenantId AND enabled = TRUE ORDER BY priority DESC, created_at DESC")
    Flux<TenantRule> findEnabledByTenant(UUID tenantId);

    @Query("SELECT * FROM public.tenant_rules WHERE id = :id AND tenant_id = :tenantId")
    Mono<TenantRule> findByIdAndTenant(UUID id, UUID tenantId);

    @Query("SELECT EXISTS(SELECT 1 FROM public.tenant_rules WHERE tenant_id = :tenantId AND rule_key = :ruleKey)")
    Mono<Boolean> existsByTenantAndKey(UUID tenantId, String ruleKey);

    @Query("SELECT COUNT(*) FROM public.tenant_rules WHERE tenant_id = :tenantId AND category = :category")
    Mono<Long> countByTenantAndCategory(UUID tenantId, String category);
}

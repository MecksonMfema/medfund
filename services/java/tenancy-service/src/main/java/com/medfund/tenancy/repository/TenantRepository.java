package com.medfund.tenancy.repository;

import com.medfund.tenancy.entity.Tenant;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface TenantRepository extends R2dbcRepository<Tenant, UUID>, TenantRepositoryCustom {

    @Query("SELECT * FROM public.tenants WHERE slug = :slug")
    Mono<Tenant> findBySlug(String slug);

    @Query("SELECT * FROM public.tenants WHERE domain = :domain")
    Mono<Tenant> findByDomain(String domain);

    @Query("SELECT * FROM public.tenants WHERE status = :status ORDER BY created_at DESC")
    Flux<Tenant> findByStatus(String status);

    @Query("SELECT * FROM public.tenants ORDER BY created_at DESC")
    Flux<Tenant> findAllOrderByCreatedAtDesc();

    @Query("SELECT * FROM public.tenants WHERE keycloak_realm = :realm")
    Mono<Tenant> findByKeycloakRealm(String realm);

    @Query("SELECT EXISTS(SELECT 1 FROM public.tenants WHERE slug = :slug)")
    Mono<Boolean> existsBySlug(String slug);

    @Query("SELECT EXISTS(SELECT 1 FROM public.tenants WHERE domain = :domain)")
    Mono<Boolean> existsByDomain(String domain);
}

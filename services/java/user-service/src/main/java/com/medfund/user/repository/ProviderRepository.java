package com.medfund.user.repository;

import com.medfund.user.entity.Provider;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Platform provider registry access.
 *
 * <p>Every {@code @Query} schema-qualifies {@code public.providers}. The
 * {@link Provider} entity is already {@code @Table(schema = "public")}, so the
 * derived methods hit the platform table; leaving the {@code @Query} strings
 * unqualified made them resolve off {@code search_path} instead, which under a
 * tenant-scoped caller silently read that tenant's shadow copy. The shadow is
 * gone as of tenant migration V276, so an unqualified read is now an outright
 * "relation does not exist".
 *
 * <p>These are registry-wide reads with no tenant gate, which is correct for the
 * platform-less {@code /api/v1/providers} surface they serve. A tenant-scoped
 * read of providers belongs behind a {@code public.provider_tenants} membership
 * check instead (CLAUDE.md Critical Rule 2).
 */
public interface ProviderRepository extends R2dbcRepository<Provider, UUID> {

    @Query("SELECT * FROM public.providers WHERE registration_number = :registrationNumber")
    Mono<Provider> findByRegistrationNumber(String registrationNumber);

    @Query("SELECT * FROM public.providers WHERE status = :status ORDER BY name")
    Flux<Provider> findByStatus(String status);

    @Query("SELECT * FROM public.providers WHERE keycloak_user_id = :keycloakUserId")
    Mono<Provider> findByKeycloakUserId(String keycloakUserId);

    @Query("SELECT * FROM public.providers WHERE specialty = :specialty AND status = 'active' ORDER BY name")
    Flux<Provider> findBySpecialty(String specialty);

    @Query("SELECT * FROM public.providers WHERE LOWER(name) LIKE LOWER(CONCAT('%', :query, '%')) OR LOWER(registration_number) LIKE LOWER(CONCAT('%', :query, '%')) ORDER BY name")
    Flux<Provider> search(String query);

    @Query("SELECT * FROM public.providers ORDER BY created_at DESC")
    Flux<Provider> findAllOrderByCreatedAtDesc();

    @Query("SELECT * FROM public.providers ORDER BY created_at DESC LIMIT :size OFFSET :offset")
    Flux<Provider> findPage(int size, long offset);

    @Query("SELECT COUNT(*) FROM public.providers")
    Mono<Long> countAll();

    @Query("""
        SELECT * FROM public.providers
        WHERE (:q IS NULL OR LOWER(name) LIKE LOWER(CONCAT('%', :q, '%'))
               OR LOWER(registration_number) LIKE LOWER(CONCAT('%', :q, '%')))
          AND (:status IS NULL OR status = :status)
          AND (:providerType IS NULL OR provider_type = :providerType)
        ORDER BY created_at DESC
        LIMIT :size OFFSET :offset
        """)
    Flux<Provider> searchPage(String q, String status, String providerType, int size, long offset);

    @Query("""
        SELECT COUNT(*) FROM public.providers
        WHERE (:q IS NULL OR LOWER(name) LIKE LOWER(CONCAT('%', :q, '%'))
               OR LOWER(registration_number) LIKE LOWER(CONCAT('%', :q, '%')))
          AND (:status IS NULL OR status = :status)
          AND (:providerType IS NULL OR provider_type = :providerType)
        """)
    Mono<Long> countSearch(String q, String status, String providerType);

    @Query("SELECT EXISTS(SELECT 1 FROM public.providers WHERE registration_number = :registrationNumber)")
    Mono<Boolean> existsByRegistrationNumber(String registrationNumber);
}

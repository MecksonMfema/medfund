package com.medfund.user.repository;

import com.medfund.user.entity.Provider;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface ProviderRepository extends R2dbcRepository<Provider, UUID> {

    @Query("SELECT * FROM providers WHERE registration_number = :registrationNumber")
    Mono<Provider> findByRegistrationNumber(String registrationNumber);

    @Query("SELECT * FROM providers WHERE status = :status ORDER BY name")
    Flux<Provider> findByStatus(String status);

    @Query("SELECT * FROM providers WHERE keycloak_user_id = :keycloakUserId")
    Mono<Provider> findByKeycloakUserId(String keycloakUserId);

    @Query("SELECT * FROM providers WHERE specialty = :specialty AND status = 'active' ORDER BY name")
    Flux<Provider> findBySpecialty(String specialty);

    @Query("SELECT * FROM providers WHERE LOWER(name) LIKE LOWER(CONCAT('%', :query, '%')) OR LOWER(registration_number) LIKE LOWER(CONCAT('%', :query, '%')) ORDER BY name")
    Flux<Provider> search(String query);

    @Query("SELECT * FROM providers ORDER BY created_at DESC")
    Flux<Provider> findAllOrderByCreatedAtDesc();

    @Query("SELECT * FROM providers ORDER BY created_at DESC LIMIT :size OFFSET :offset")
    Flux<Provider> findPage(int size, long offset);

    @Query("SELECT COUNT(*) FROM providers")
    Mono<Long> countAll();

    @Query("""
        SELECT * FROM providers
        WHERE (:q IS NULL OR LOWER(name) LIKE LOWER(CONCAT('%', :q, '%'))
               OR LOWER(registration_number) LIKE LOWER(CONCAT('%', :q, '%')))
          AND (:status IS NULL OR status = :status)
          AND (:providerType IS NULL OR provider_type = :providerType)
        ORDER BY created_at DESC
        LIMIT :size OFFSET :offset
        """)
    Flux<Provider> searchPage(String q, String status, String providerType, int size, long offset);

    @Query("""
        SELECT COUNT(*) FROM providers
        WHERE (:q IS NULL OR LOWER(name) LIKE LOWER(CONCAT('%', :q, '%'))
               OR LOWER(registration_number) LIKE LOWER(CONCAT('%', :q, '%')))
          AND (:status IS NULL OR status = :status)
          AND (:providerType IS NULL OR provider_type = :providerType)
        """)
    Mono<Long> countSearch(String q, String status, String providerType);

    @Query("SELECT EXISTS(SELECT 1 FROM providers WHERE registration_number = :registrationNumber)")
    Mono<Boolean> existsByRegistrationNumber(String registrationNumber);
}

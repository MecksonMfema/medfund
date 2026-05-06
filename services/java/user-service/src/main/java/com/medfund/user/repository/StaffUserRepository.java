package com.medfund.user.repository;

import com.medfund.user.entity.StaffUser;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface StaffUserRepository extends R2dbcRepository<StaffUser, UUID> {

    /** Platform super_admin users — tenant_id IS NULL. */
    @Query("SELECT * FROM public.staff_users WHERE tenant_id IS NULL ORDER BY created_at DESC")
    Flux<StaffUser> findPlatformStaff();

    /** All staff belonging to a specific tenant. */
    @Query("SELECT * FROM public.staff_users WHERE tenant_id = :tenantId ORDER BY created_at DESC")
    Flux<StaffUser> findByTenantId(UUID tenantId);

    @Query("SELECT * FROM public.staff_users ORDER BY created_at DESC")
    Flux<StaffUser> findAllOrderByCreatedAtDesc();

    @Query("SELECT * FROM public.staff_users WHERE email = :email")
    Mono<StaffUser> findByEmail(String email);

    @Query("SELECT * FROM public.staff_users WHERE keycloak_user_id = :keycloakUserId")
    Mono<StaffUser> findByKeycloakUserId(String keycloakUserId);

    @Query("SELECT * FROM public.staff_users WHERE tenant_id IS NULL AND status = :status ORDER BY created_at DESC")
    Flux<StaffUser> findPlatformStaffByStatus(String status);

    @Query("SELECT * FROM public.staff_users WHERE tenant_id = :tenantId AND status = :status ORDER BY created_at DESC")
    Flux<StaffUser> findByTenantIdAndStatus(UUID tenantId, String status);

    @Query("SELECT * FROM public.staff_users WHERE realm_role = :realmRole ORDER BY created_at DESC")
    Flux<StaffUser> findByRealmRole(String realmRole);

    @Query("SELECT EXISTS(SELECT 1 FROM public.staff_users WHERE email = :email)")
    Mono<Boolean> existsByEmail(String email);

    @Query("""
        SELECT * FROM public.staff_users
        WHERE tenant_id IS NULL
          AND (lower(first_name) LIKE lower(concat('%', :query, '%'))
           OR lower(last_name)   LIKE lower(concat('%', :query, '%'))
           OR lower(email)       LIKE lower(concat('%', :query, '%')))
        ORDER BY created_at DESC
        """)
    Flux<StaffUser> searchPlatformStaff(String query);

    @Query("""
        SELECT * FROM public.staff_users
        WHERE tenant_id = :tenantId
          AND (lower(first_name) LIKE lower(concat('%', :query, '%'))
           OR lower(last_name)   LIKE lower(concat('%', :query, '%'))
           OR lower(email)       LIKE lower(concat('%', :query, '%')))
        ORDER BY created_at DESC
        """)
    Flux<StaffUser> searchByTenant(UUID tenantId, String query);

    // ── Cursor pagination ──────────────────────────────────────────────────

    @Query("SELECT * FROM public.staff_users WHERE tenant_id IS NULL ORDER BY created_at DESC, id DESC LIMIT :limit")
    Flux<StaffUser> findPlatformFirstPage(int limit);

    @Query("""
        SELECT * FROM public.staff_users WHERE tenant_id IS NULL
        AND (created_at < :cursorTs OR (created_at = :cursorTs AND id < :cursorId))
        ORDER BY created_at DESC, id DESC LIMIT :limit
        """)
    Flux<StaffUser> findPlatformNextPage(java.time.Instant cursorTs, UUID cursorId, int limit);

    @Query("SELECT * FROM public.staff_users WHERE tenant_id = :tenantId ORDER BY created_at DESC, id DESC LIMIT :limit")
    Flux<StaffUser> findTenantFirstPage(UUID tenantId, int limit);

    @Query("""
        SELECT * FROM public.staff_users WHERE tenant_id = :tenantId
        AND (created_at < :cursorTs OR (created_at = :cursorTs AND id < :cursorId))
        ORDER BY created_at DESC, id DESC LIMIT :limit
        """)
    Flux<StaffUser> findTenantNextPage(UUID tenantId, java.time.Instant cursorTs, UUID cursorId, int limit);

    @Query("""
        SELECT * FROM public.staff_users WHERE tenant_id IS NULL
        AND (lower(first_name) LIKE lower(concat('%', :q, '%'))
          OR lower(last_name)  LIKE lower(concat('%', :q, '%'))
          OR lower(email)      LIKE lower(concat('%', :q, '%')))
        ORDER BY created_at DESC, id DESC LIMIT :limit
        """)
    Flux<StaffUser> searchPlatformFirstPage(String q, int limit);

    @Query("""
        SELECT * FROM public.staff_users WHERE tenant_id IS NULL
        AND (lower(first_name) LIKE lower(concat('%', :q, '%'))
          OR lower(last_name)  LIKE lower(concat('%', :q, '%'))
          OR lower(email)      LIKE lower(concat('%', :q, '%')))
        AND (created_at < :cursorTs OR (created_at = :cursorTs AND id < :cursorId))
        ORDER BY created_at DESC, id DESC LIMIT :limit
        """)
    Flux<StaffUser> searchPlatformNextPage(String q, java.time.Instant cursorTs, UUID cursorId, int limit);

    @Query("""
        SELECT * FROM public.staff_users WHERE tenant_id = :tenantId
        AND (lower(first_name) LIKE lower(concat('%', :q, '%'))
          OR lower(last_name)  LIKE lower(concat('%', :q, '%'))
          OR lower(email)      LIKE lower(concat('%', :q, '%')))
        ORDER BY created_at DESC, id DESC LIMIT :limit
        """)
    Flux<StaffUser> searchTenantFirstPage(UUID tenantId, String q, int limit);

    @Query("""
        SELECT * FROM public.staff_users WHERE tenant_id = :tenantId
        AND (lower(first_name) LIKE lower(concat('%', :q, '%'))
          OR lower(last_name)  LIKE lower(concat('%', :q, '%'))
          OR lower(email)      LIKE lower(concat('%', :q, '%')))
        AND (created_at < :cursorTs OR (created_at = :cursorTs AND id < :cursorId))
        ORDER BY created_at DESC, id DESC LIMIT :limit
        """)
    Flux<StaffUser> searchTenantNextPage(UUID tenantId, String q, java.time.Instant cursorTs, UUID cursorId, int limit);
}

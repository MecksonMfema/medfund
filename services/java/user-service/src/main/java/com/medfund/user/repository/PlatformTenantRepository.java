package com.medfund.user.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Read-only lookup against {@code public.tenants} for the provider-membership
 * surface. Two callers need it and neither wants the tenancy-service round
 * trip: the link endpoint needs a real 404 when the tenant UUID is unknown
 * (the FK would otherwise surface as an opaque constraint violation), and the
 * audit envelope needs the tenant's display name so
 * {@code AuditEvent.entityName} stays human-readable rather than a pair of
 * UUIDs.
 *
 * <p>Schema-qualified on purpose: {@code public.tenants} is genuinely
 * platform-wide, and user-service can be called under a tenant search_path.
 */
@Repository
@RequiredArgsConstructor
public class PlatformTenantRepository {

    private final DatabaseClient db;

    /** Tenant display name, or an empty Mono when no such tenant exists. */
    public Mono<String> findNameById(UUID tenantId) {
        return db.sql("SELECT name FROM public.tenants WHERE id = :tenantId")
                .bind("tenantId", tenantId)
                .map((row, meta) -> row.get("name", String.class))
                .one();
    }
}

package com.medfund.shared.security;

import reactor.core.publisher.Mono;

import java.util.Set;
import java.util.UUID;

/**
 * Resolves the effective permissions of a user inside the active tenant.
 *
 * <p>The default implementation reads {@code user_roles} JOIN {@code role_permissions}
 * from the tenant schema (chosen via the existing {@code TenantAwareConnectionFactory}).
 * Services can replace it with a custom impl by registering their own bean if they
 * need a different storage model — e.g. a service that doesn't use the standard
 * tenant schema.
 */
public interface PermissionResolver {

    /**
     * Returns the union of permissions across every role the user has in the
     * tenant whose schema is currently selected by the reactive context.
     * Returns an empty set when the user has no roles or no tenant is in scope.
     */
    Mono<Set<String>> resolve(UUID userId);

    /** Drops the cached entry for one user. Called from Kafka invalidation consumer. */
    default void invalidate(String tenantId, UUID userId) { /* no-op for non-caching impls */ }

    /** Drops every cached entry for a tenant. Called when a role-permission edit affects all users. */
    default void invalidateTenant(String tenantId) { /* no-op */ }
}

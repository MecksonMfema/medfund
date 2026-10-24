package com.medfund.shared.entitlement;

import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Read-side accessor for per-tenant module entitlements. Backed by the
 * {@code public.tenant_module_entitlements} table owned by tenancy-service.
 * Values are cached in-memory for up to 30 seconds — an entitlement toggle
 * takes at most that long to propagate to any gated service, with the Kafka
 * invalidation on {@code platform.module-entitlements.v1} normally landing it
 * within a round trip.
 *
 * <p>Unlike {@code FlagRegistry}, the lookup is keyed by BOTH the tenant and
 * the module — the whole point of the seam is per-tenant gating. Absent rows
 * are treated as {@code false}: a module is off until it is explicitly sold,
 * and a fresh tenancy-service that has not yet seen a toggle must fail closed,
 * never hand a paid module away during uncertainty (G3).
 */
public interface TenantEntitlementRegistry {

    Mono<Boolean> isEntitled(UUID tenantId, ModuleKey module);

    /**
     * Drops any cached value for {@code (tenantId, rawKey)} so the next
     * {@link #isEntitled} re-reads it. Called by
     * {@link EntitlementInvalidationConsumer} when tenancy-service broadcasts a
     * toggle, turning the 30-second TTL from the propagation mechanism into a
     * fallback for a missed or undelivered event.
     *
     * <p>{@code rawKey} is the wire value from the Kafka payload and may not
     * match any {@link ModuleKey} (enum drift between services mid-deploy), so
     * implementations must tolerate an unknown key rather than throw. Defaults
     * to a no-op for cache-free implementations and test stubs.
     */
    default void invalidate(String tenantId, String rawKey) {
        // no cache to drop
    }
}

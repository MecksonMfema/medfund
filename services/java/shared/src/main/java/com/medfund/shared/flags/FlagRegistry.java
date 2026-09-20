package com.medfund.shared.flags;

import reactor.core.publisher.Mono;

/**
 * Read-side accessor for platform feature flags. Backed by the
 * {@code public.platform_feature_flags} table owned by tenancy-service.
 * Values are cached in-memory for up to 30 seconds — a flag flip takes at
 * most that long to propagate to any AI-consuming service.
 *
 * <p>Every AI-consuming Java service (claims-service, contributions-service,
 * finance-service) autowires this bean. The Elixir chat_service reads flags
 * via {@code GET /api/v1/platform/feature-flags/{key}} against tenancy-service
 * instead of hitting Postgres directly.
 *
 * <p>Missing rows are treated as {@code false} — a fresh tenancy-service that
 * has not yet run the seeder is safer defaulting off than throwing.
 */
public interface FlagRegistry {

    Mono<Boolean> isEnabled(PlatformFlag flag);

    /**
     * Drops any cached value for {@code rawKey} so the next
     * {@link #isEnabled} re-reads it. Called by
     * {@link FlagInvalidationConsumer} when tenancy-service broadcasts a
     * toggle, which turns the 30-second TTL from the propagation mechanism
     * into a fallback for a missed or undelivered event.
     *
     * <p>{@code rawKey} is the wire value from the Kafka payload and may not
     * match any {@link PlatformFlag} (enum drift between services mid-deploy),
     * so implementations must tolerate an unknown key rather than throw.
     * Defaults to a no-op for cache-free implementations and test stubs.
     */
    default void invalidate(String rawKey) {
        // no cache to drop
    }
}

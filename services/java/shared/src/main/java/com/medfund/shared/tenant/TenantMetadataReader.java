package com.medfund.shared.tenant;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Reader for platform-wide tenant metadata that guard aspects
 * ({@code JurisdictionGuardAspect}, {@code CountryGuardAspect}) consult before
 * proceeding with a controller invocation. Reads {@code jurisdiction_code}
 * and {@code country_code} directly from {@code public.tenants} — the same
 * cross-service-in-process pattern as
 * {@link com.medfund.shared.report.ReportEnablementReader}, avoiding an
 * additional HTTP hop to tenancy-service on every request.
 *
 * <p>Uses the {@code public.} prefix per {@code bug_public_prefix_silent_rollback} —
 * {@code tenants} is a platform-wide table (V001, V131 for the jurisdiction column).
 *
 * <p>Errors resolve to a null-metadata tuple rather than propagating; the caller
 * (a guard aspect) then denies because a null jurisdiction or country cannot
 * match any allowed value. Failing closed on a DB hiccup keeps the security
 * gate honest.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TenantMetadataReader {

    /** {@code (jurisdictionCode, countryCode)} tuple. Either half may be null. */
    public record TenantMetadata(String jurisdictionCode, String countryCode) {

        public static TenantMetadata empty() {
            return new TenantMetadata(null, null);
        }
    }

    private final DatabaseClient databaseClient;

    /** Explicit-tenant variant — call from Kafka consumers and scheduled jobs. */
    public Mono<TenantMetadata> load(UUID tenantId) {
        if (tenantId == null) return Mono.just(TenantMetadata.empty());
        return databaseClient.sql("""
                SELECT jurisdiction_code, country_code FROM public.tenants
                 WHERE id = :id
                """)
                .bind("id", tenantId)
                .map((row, meta) -> new TenantMetadata(
                        row.get("jurisdiction_code", String.class),
                        row.get("country_code", String.class)))
                .one()
                .defaultIfEmpty(TenantMetadata.empty())
                .onErrorResume(err -> {
                    log.warn("[tenant-meta] load failed for {}: {} — denying by empty",
                            tenantId, err.getMessage());
                    return Mono.just(TenantMetadata.empty());
                });
    }

    /**
     * Reactive-context variant — pulls the tenant ID from
     * {@link TenantContext} (populated by {@code TenantWebFilter}) and
     * delegates to {@link #load(UUID)}. Returns an empty tuple when there is
     * no tenant in scope so platform-scope endpoints don't accidentally read
     * a random row.
     */
    public Mono<TenantMetadata> loadFromContext() {
        return Mono.deferContextual(ctx -> {
            String raw = TenantContext.get(ctx);
            if (raw == null || raw.isBlank()) return Mono.just(TenantMetadata.empty());
            try {
                return load(UUID.fromString(raw));
            } catch (IllegalArgumentException e) {
                return Mono.just(TenantMetadata.empty());
            }
        });
    }
}

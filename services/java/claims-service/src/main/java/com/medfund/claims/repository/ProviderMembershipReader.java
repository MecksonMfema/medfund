package com.medfund.claims.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Two existence reads against the platform provider junctions, used by
 * {@code ClaimService} to reject a claim whose provider is not contracted with
 * the submitting tenant, or is not tagged to serve the claim's insurance line.
 *
 * <p>Colocated with the caller rather than fetched from user-service over HTTP:
 * both statements are single-row existence checks on tables every tenant role
 * can already read (public V185 grants), and a synchronous cross-service hop on
 * the claim-submit hot path would buy nothing.
 *
 * <p>Both tables are genuinely platform-wide, so {@code public.} is the correct
 * qualification here, the opposite of the tenant-table rule where a
 * {@code public.} prefix would silently roll back.
 */
@Component
@RequiredArgsConstructor
public class ProviderMembershipReader {

    private final DatabaseClient db;

    /**
     * Whether the provider has an ACTIVE contract with this tenant. A
     * {@code pending}, {@code suspended} or {@code terminated} membership reads
     * as "not a member": each of those states means the tenant should not be
     * accruing new liability against that provider.
     */
    public Mono<Boolean> isMember(UUID providerId, UUID tenantId) {
        return db.sql("""
                SELECT EXISTS (SELECT 1 FROM public.provider_tenants
                                WHERE provider_id = :providerId
                                  AND tenant_id = :tenantId
                                  AND status = 'active') AS present
                """)
                .bind("providerId", providerId)
                .bind("tenantId", tenantId)
                .map((row, meta) -> row.get("present", Boolean.class))
                .one()
                .defaultIfEmpty(Boolean.FALSE);
    }

    /** Whether the provider carries a tag for this insurance line. */
    public Mono<Boolean> servesLine(UUID providerId, String line) {
        return db.sql("""
                SELECT EXISTS (SELECT 1 FROM public.provider_insurance_lines
                                WHERE provider_id = :providerId
                                  AND insurance_line = :line) AS present
                """)
                .bind("providerId", providerId)
                .bind("line", line.toUpperCase())
                .map((row, meta) -> row.get("present", Boolean.class))
                .one()
                .defaultIfEmpty(Boolean.FALSE);
    }
}

package com.medfund.user.repository;

import com.medfund.user.entity.ProviderInsuranceLine;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Composite-key repository for {@code public.provider_insurance_lines}.
 * Same hand-rolled shape as {@link ProviderTenantRepository} — R2DBC's
 * {@code ReactiveCrudRepository} does not support composite keys.
 *
 * <p>{@code existsByProviderIdAndLine} is the read that
 * {@code ClaimService.validateProviderMembership} keys on: a claim whose
 * scheme line is not tagged on the referenced provider is rejected with 422.
 */
@Repository
@RequiredArgsConstructor
public class ProviderInsuranceLineRepository {

    private final DatabaseClient db;

    public Flux<ProviderInsuranceLine> findByProviderId(UUID providerId) {
        return db.sql("""
                SELECT provider_id, insurance_line, created_at
                  FROM public.provider_insurance_lines
                 WHERE provider_id = :providerId
                 ORDER BY insurance_line
                """)
                .bind("providerId", providerId)
                .map(ProviderInsuranceLineRepository::mapRow)
                .all();
    }

    /**
     * (providerId, line) pairs for a page of providers, in one round trip.
     * Same rationale as {@code ProviderTenantRepository.findTenantIdsByProviderIds}.
     */
    public Flux<Map.Entry<UUID, String>> findByProviderIds(Collection<UUID> providerIds) {
        if (providerIds.isEmpty()) return Flux.empty();
        return db.sql("""
                SELECT provider_id, insurance_line
                  FROM public.provider_insurance_lines
                 WHERE provider_id IN (:providerIds)
                 ORDER BY provider_id, insurance_line
                """)
                .bind("providerIds", providerIds)
                .map((row, meta) -> Map.entry(
                        row.get("provider_id", UUID.class),
                        row.get("insurance_line", String.class)))
                .all();
    }

    public Mono<ProviderInsuranceLine> insert(UUID providerId, String insuranceLine) {
        return db.sql("""
                INSERT INTO public.provider_insurance_lines (provider_id, insurance_line)
                     VALUES (:providerId, :insuranceLine)
                  RETURNING provider_id, insurance_line, created_at
                """)
                .bind("providerId", providerId)
                .bind("insuranceLine", insuranceLine)
                .map(ProviderInsuranceLineRepository::mapRow)
                .one();
    }

    public Mono<Long> delete(UUID providerId, String insuranceLine) {
        return db.sql("""
                DELETE FROM public.provider_insurance_lines
                 WHERE provider_id = :providerId AND insurance_line = :insuranceLine
                """)
                .bind("providerId", providerId)
                .bind("insuranceLine", insuranceLine)
                .fetch()
                .rowsUpdated();
    }

    public Mono<Boolean> existsByProviderIdAndLine(UUID providerId, String insuranceLine) {
        return db.sql("""
                SELECT EXISTS (SELECT 1 FROM public.provider_insurance_lines
                                WHERE provider_id = :providerId
                                  AND insurance_line = :insuranceLine) AS present
                """)
                .bind("providerId", providerId)
                .bind("insuranceLine", insuranceLine)
                .map((row, meta) -> row.get("present", Boolean.class))
                .one()
                .defaultIfEmpty(Boolean.FALSE);
    }

    private static ProviderInsuranceLine mapRow(Row row, RowMetadata meta) {
        ProviderInsuranceLine l = new ProviderInsuranceLine();
        l.setProviderId(row.get("provider_id", UUID.class));
        l.setInsuranceLine(row.get("insurance_line", String.class));
        l.setCreatedAt(row.get("created_at", OffsetDateTime.class));
        return l;
    }
}

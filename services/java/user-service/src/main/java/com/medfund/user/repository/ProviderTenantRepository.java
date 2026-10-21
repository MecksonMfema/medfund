package com.medfund.user.repository;

import com.medfund.user.entity.ProviderTenant;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Composite-key repository for {@code public.provider_tenants}. R2DBC's
 * {@code ReactiveCrudRepository} doesn't support composite keys, so every
 * operation is hand-rolled via {@link DatabaseClient} — same shape as
 * {@code TreatyParticipantRepository} in finance-service.
 *
 * <p>Every statement schema-qualifies {@code public.} on purpose: the table
 * is genuinely platform-wide, and user-service can be called under a tenant
 * search_path where an unqualified name would resolve somewhere else.
 */
@Repository
@RequiredArgsConstructor
public class ProviderTenantRepository {

    private static final String COLUMNS = """
            provider_id, tenant_id, status, network_tier, in_network,
            contract_effective_from, contract_effective_to,
            credit_limit, credit_limit_currency, tariff_agreement_id,
            created_at, updated_at, created_by, updated_by
            """;

    private final DatabaseClient db;

    public Flux<ProviderTenant> findByProviderId(UUID providerId) {
        return db.sql("SELECT " + COLUMNS + " FROM public.provider_tenants"
                        + " WHERE provider_id = :providerId ORDER BY tenant_id")
                .bind("providerId", providerId)
                .map(ProviderTenantRepository::mapRow)
                .all();
    }

    public Mono<ProviderTenant> findByProviderIdAndTenantId(UUID providerId, UUID tenantId) {
        return db.sql("SELECT " + COLUMNS + " FROM public.provider_tenants"
                        + " WHERE provider_id = :providerId AND tenant_id = :tenantId")
                .bind("providerId", providerId)
                .bind("tenantId", tenantId)
                .map(ProviderTenantRepository::mapRow)
                .one();
    }

    /**
     * (providerId, tenantId) pairs for a page of providers, in one round trip.
     * Backs the {@code tenantIds} pill column on {@code GET /api/v1/providers}:
     * a request per row would be 20 extra calls per page.
     */
    public Flux<Map.Entry<UUID, UUID>> findTenantIdsByProviderIds(Collection<UUID> providerIds) {
        if (providerIds.isEmpty()) return Flux.empty();
        return db.sql("SELECT provider_id, tenant_id FROM public.provider_tenants"
                        + " WHERE provider_id IN (:providerIds) ORDER BY provider_id, tenant_id")
                .bind("providerIds", providerIds)
                .map((row, meta) -> Map.entry(
                        row.get("provider_id", UUID.class),
                        row.get("tenant_id", UUID.class)))
                .all();
    }

    public Mono<ProviderTenant> insert(ProviderTenant p) {
        DatabaseClient.GenericExecuteSpec spec = db.sql("""
                INSERT INTO public.provider_tenants
                    (provider_id, tenant_id, status, network_tier, in_network,
                     contract_effective_from, contract_effective_to,
                     credit_limit, credit_limit_currency, tariff_agreement_id, created_by)
                VALUES (:providerId, :tenantId, :status, :networkTier, :inNetwork,
                        COALESCE(:effectiveFrom, CURRENT_DATE), :effectiveTo,
                        :creditLimit, :creditLimitCurrency, :tariffAgreementId, :createdBy)
                RETURNING provider_id, tenant_id, status, network_tier, in_network,
                          contract_effective_from, contract_effective_to,
                          credit_limit, credit_limit_currency, tariff_agreement_id,
                          created_at, updated_at, created_by, updated_by
                """)
                .bind("providerId", p.getProviderId())
                .bind("tenantId", p.getTenantId())
                .bind("status", p.getStatus() != null ? p.getStatus() : "active")
                .bind("networkTier", p.getNetworkTier() != null ? p.getNetworkTier() : "STANDARD")
                .bind("inNetwork", p.getInNetwork() != null ? p.getInNetwork() : Boolean.TRUE);

        spec = bindIfPresent(spec, "effectiveFrom", p.getContractEffectiveFrom(), LocalDate.class);
        spec = bindIfPresent(spec, "effectiveTo", p.getContractEffectiveTo(), LocalDate.class);
        spec = bindIfPresent(spec, "creditLimit", p.getCreditLimit(), BigDecimal.class);
        spec = bindIfPresent(spec, "creditLimitCurrency", p.getCreditLimitCurrency(), String.class);
        spec = bindIfPresent(spec, "tariffAgreementId", p.getTariffAgreementId(), UUID.class);
        spec = bindIfPresent(spec, "createdBy", p.getCreatedBy(), UUID.class);

        return spec.map(ProviderTenantRepository::mapRow).one();
    }

    public Mono<Long> delete(UUID providerId, UUID tenantId) {
        return db.sql("DELETE FROM public.provider_tenants"
                        + " WHERE provider_id = :providerId AND tenant_id = :tenantId")
                .bind("providerId", providerId)
                .bind("tenantId", tenantId)
                .fetch()
                .rowsUpdated();
    }

    /**
     * spring-r2dbc's GenericExecuteSpec has no bindNullable in 6.1.x — an
     * unbound named parameter surfaces as "binding parameter missing", so
     * nullable columns must be explicitly bound NULL with their type.
     */
    private static <T> DatabaseClient.GenericExecuteSpec bindIfPresent(
            DatabaseClient.GenericExecuteSpec spec, String name, T value, Class<T> type) {
        return value != null ? spec.bind(name, value) : spec.bindNull(name, type);
    }

    private static ProviderTenant mapRow(Row row, RowMetadata meta) {
        ProviderTenant p = new ProviderTenant();
        p.setProviderId(row.get("provider_id", UUID.class));
        p.setTenantId(row.get("tenant_id", UUID.class));
        p.setStatus(row.get("status", String.class));
        p.setNetworkTier(row.get("network_tier", String.class));
        p.setInNetwork(row.get("in_network", Boolean.class));
        p.setContractEffectiveFrom(row.get("contract_effective_from", LocalDate.class));
        p.setContractEffectiveTo(row.get("contract_effective_to", LocalDate.class));
        p.setCreditLimit(row.get("credit_limit", BigDecimal.class));
        p.setCreditLimitCurrency(row.get("credit_limit_currency", String.class));
        p.setTariffAgreementId(row.get("tariff_agreement_id", UUID.class));
        p.setCreatedAt(row.get("created_at", OffsetDateTime.class));
        p.setUpdatedAt(row.get("updated_at", OffsetDateTime.class));
        p.setCreatedBy(row.get("created_by", UUID.class));
        p.setUpdatedBy(row.get("updated_by", UUID.class));
        return p;
    }
}

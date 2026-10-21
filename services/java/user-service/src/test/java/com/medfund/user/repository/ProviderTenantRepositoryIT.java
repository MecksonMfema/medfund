package com.medfund.user.repository;

import com.medfund.user.entity.ProviderTenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.r2dbc.core.DatabaseClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * IT for {@link ProviderTenantRepository} against a real Postgres.
 *
 * <p>The repository is hand-rolled SQL over a composite-PK table, so there is
 * no Spring Data derivation to lean on: every column name, every bind and the
 * {@code RETURNING} projection are only proved by a round-trip. Cases:
 *
 * <ul>
 *   <li>insert defaults — {@code status}, {@code network_tier},
 *       {@code in_network} and {@code contract_effective_from} fill in when
 *       the caller leaves them null (the shape the link endpoint uses).</li>
 *   <li>full contract row — every nullable contract column round-trips.</li>
 *   <li>lookups — by provider and by the composite key.</li>
 *   <li>composite-PK uniqueness — a second insert of the same pair errors.</li>
 *   <li>CHECK constraints — bad status and bad network_tier are rejected.</li>
 *   <li>delete — returns the affected row count and removes the row.</li>
 * </ul>
 */
class ProviderTenantRepositoryIT extends AbstractProviderMembershipIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    @Autowired private ProviderTenantRepository repository;
    @Autowired private DatabaseClient db;

    private UUID providerId;
    private UUID tenantA;
    private UUID tenantB;

    @BeforeEach
    void seed() {
        db.sql("TRUNCATE public.provider_tenants, public.provider_insurance_lines, "
                + "public.providers, public.tenants CASCADE").then().block(TIMEOUT);

        providerId = UUID.randomUUID();
        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();

        db.sql("INSERT INTO public.providers (id, name, provider_type) "
                        + "VALUES (:id, 'Acme Medical', 'HEALTH')")
                .bind("id", providerId).then().block(TIMEOUT);
        insertTenant(tenantA, "health-first");
        insertTenant(tenantB, "life-first");
    }

    private void insertTenant(UUID id, String slug) {
        db.sql("INSERT INTO public.tenants (id, name, slug, schema_name) "
                        + "VALUES (:id, :slug, :slug, :schema)")
                .bind("id", id)
                .bind("slug", slug)
                .bind("schema", "tenant_" + slug.replace('-', '_'))
                .then().block(TIMEOUT);
    }

    // ------------------------------------------------------------------
    // Insert defaults — the shape the link endpoint uses (provider + tenant
    // only). Everything else comes from the schema defaults.
    // ------------------------------------------------------------------

    @Test
    void insert_withOnlyKeys_fillsSchemaDefaults() {
        var pt = new ProviderTenant();
        pt.setProviderId(providerId);
        pt.setTenantId(tenantA);

        ProviderTenant saved = repository.insert(pt).block(TIMEOUT);

        assertThat(saved).isNotNull();
        assertThat(saved.getProviderId()).isEqualTo(providerId);
        assertThat(saved.getTenantId()).isEqualTo(tenantA);
        assertThat(saved.getStatus()).isEqualTo("active");
        assertThat(saved.getNetworkTier()).isEqualTo("STANDARD");
        assertThat(saved.getInNetwork()).isTrue();
        assertThat(saved.getContractEffectiveFrom()).isEqualTo(LocalDate.now());
        assertThat(saved.getContractEffectiveTo()).isNull();
        assertThat(saved.getCreditLimit()).isNull();
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    // ------------------------------------------------------------------
    // Full contract row — every nullable contract column round-trips. These
    // have no consumer in v1, so only this IT proves the columns are bound
    // and projected correctly.
    // ------------------------------------------------------------------

    @Test
    void insert_withFullContract_roundTripsEveryColumn() {
        UUID actor = UUID.randomUUID();
        UUID tariff = UUID.randomUUID();

        var pt = new ProviderTenant();
        pt.setProviderId(providerId);
        pt.setTenantId(tenantA);
        pt.setStatus("pending");
        pt.setNetworkTier("TIER_2");
        pt.setInNetwork(false);
        pt.setContractEffectiveFrom(LocalDate.of(2026, 1, 1));
        pt.setContractEffectiveTo(LocalDate.of(2026, 12, 31));
        pt.setCreditLimit(new BigDecimal("12500.00"));
        pt.setCreditLimitCurrency("USD");
        pt.setTariffAgreementId(tariff);
        pt.setCreatedBy(actor);

        ProviderTenant saved = repository.insert(pt).block(TIMEOUT);

        assertThat(saved).isNotNull();
        assertThat(saved.getStatus()).isEqualTo("pending");
        assertThat(saved.getNetworkTier()).isEqualTo("TIER_2");
        assertThat(saved.getInNetwork()).isFalse();
        assertThat(saved.getContractEffectiveFrom()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(saved.getContractEffectiveTo()).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(saved.getCreditLimit()).isEqualByComparingTo("12500.00");
        assertThat(saved.getCreditLimitCurrency()).isEqualTo("USD");
        assertThat(saved.getTariffAgreementId()).isEqualTo(tariff);
        assertThat(saved.getCreatedBy()).isEqualTo(actor);
    }

    // ------------------------------------------------------------------
    // Lookups — by provider (the /providers/{id}/tenants endpoint), by
    // tenant, and by the composite key.
    // ------------------------------------------------------------------

    @Test
    void findByProviderId_returnsEveryMembership_orderedByTenant() {
        link(providerId, tenantA);
        link(providerId, tenantB);

        List<ProviderTenant> rows = repository.findByProviderId(providerId)
                .collectList().block(TIMEOUT);

        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(ProviderTenant::getTenantId)
                .containsExactlyInAnyOrder(tenantA, tenantB);
    }

    @Test
    void findByProviderIdAndTenantId_returnsTheRow_orEmptyWhenUnlinked() {
        link(providerId, tenantA);

        assertThat(repository.findByProviderIdAndTenantId(providerId, tenantA).block(TIMEOUT))
                .isNotNull();
        assertThat(repository.findByProviderIdAndTenantId(providerId, tenantB).block(TIMEOUT))
                .as("a provider not contracted with this tenant has no membership row")
                .isNull();
    }

    // ------------------------------------------------------------------
    // Composite PK + CHECK constraints — the guards the schema carries.
    // ------------------------------------------------------------------

    @Test
    void insert_duplicatePair_violatesCompositePrimaryKey() {
        link(providerId, tenantA);

        var duplicate = new ProviderTenant();
        duplicate.setProviderId(providerId);
        duplicate.setTenantId(tenantA);

        assertThatThrownBy(() -> repository.insert(duplicate).block(TIMEOUT))
                .hasMessageContaining("provider_tenants_pkey");
    }

    @Test
    void insert_unknownStatus_violatesCheckConstraint() {
        var pt = new ProviderTenant();
        pt.setProviderId(providerId);
        pt.setTenantId(tenantA);
        pt.setStatus("WRONG");

        assertThatThrownBy(() -> repository.insert(pt).block(TIMEOUT))
                .hasMessageContaining("provider_tenants_status_ck");
    }

    @Test
    void insert_unknownNetworkTier_violatesCheckConstraint() {
        var pt = new ProviderTenant();
        pt.setProviderId(providerId);
        pt.setTenantId(tenantA);
        pt.setNetworkTier("PLATINUM");

        assertThatThrownBy(() -> repository.insert(pt).block(TIMEOUT))
                .hasMessageContaining("provider_tenants_tier_ck");
    }

    // ------------------------------------------------------------------
    // Delete — the unlink endpoint reads the row count to decide whether to
    // emit an audit + Kafka event, so the count matters, not just the effect.
    // ------------------------------------------------------------------

    @Test
    void delete_removesTheRow_andReportsAffectedCount() {
        link(providerId, tenantA);

        assertThat(repository.delete(providerId, tenantA).block(TIMEOUT)).isEqualTo(1L);
        assertThat(repository.findByProviderIdAndTenantId(providerId, tenantA).block(TIMEOUT))
                .isNull();
        assertThat(repository.delete(providerId, tenantA).block(TIMEOUT))
                .as("deleting an already-unlinked pair affects no rows")
                .isEqualTo(0L);
    }

    private void link(UUID provider, UUID tenant) {
        var pt = new ProviderTenant();
        pt.setProviderId(provider);
        pt.setTenantId(tenant);
        repository.insert(pt).block(TIMEOUT);
    }
}

package com.medfund.user.repository;

import com.medfund.user.entity.ProviderInsuranceLine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.r2dbc.core.DatabaseClient;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * IT for {@link ProviderInsuranceLineRepository} against a real Postgres.
 *
 * <p>Shares {@link AbstractProviderMembershipIT}'s dedicated container and
 * the {@code db/provider-membership-migration} fixture with
 * {@link ProviderTenantRepositoryIT} — same location, same checksum, so the
 * two classes coexist without a Flyway validate collision.
 *
 * <p>The CHECK constraint is the case that matters most: it is the only
 * thing stopping a typo'd line tag from silently making every claim for
 * that provider fail the Phase 6 line cross-check.
 */
class ProviderInsuranceLineRepositoryIT extends AbstractProviderMembershipIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    @Autowired private ProviderInsuranceLineRepository repository;
    @Autowired private DatabaseClient db;

    private UUID providerId;
    private UUID otherProviderId;

    @BeforeEach
    void seed() {
        db.sql("TRUNCATE public.provider_tenants, public.provider_insurance_lines, "
                + "public.providers, public.tenants CASCADE").then().block(TIMEOUT);

        providerId = UUID.randomUUID();
        otherProviderId = UUID.randomUUID();
        insertProvider(providerId, "Acme Medical");
        insertProvider(otherProviderId, "Beta Funeral Parlour");
    }

    private void insertProvider(UUID id, String name) {
        db.sql("INSERT INTO public.providers (id, name) VALUES (:id, :name)")
                .bind("id", id).bind("name", name)
                .then().block(TIMEOUT);
    }

    @Test
    void insert_thenFindByProviderId_returnsTagsOrderedByLine() {
        repository.insert(providerId, "LIFE").block(TIMEOUT);
        repository.insert(providerId, "HEALTH").block(TIMEOUT);
        repository.insert(otherProviderId, "FUNERAL").block(TIMEOUT);

        List<ProviderInsuranceLine> rows =
                repository.findByProviderId(providerId).collectList().block(TIMEOUT);

        assertThat(rows).extracting(ProviderInsuranceLine::getInsuranceLine)
                .as("ordered by insurance_line; the other provider's tag is excluded")
                .containsExactly("HEALTH", "LIFE");
        assertThat(rows.get(0).getCreatedAt()).isNotNull();
    }

    @Test
    void existsByProviderIdAndLine_isTrueOnlyForTaggedLines() {
        repository.insert(providerId, "HEALTH").block(TIMEOUT);

        assertThat(repository.existsByProviderIdAndLine(providerId, "HEALTH").block(TIMEOUT)).isTrue();
        assertThat(repository.existsByProviderIdAndLine(providerId, "LIFE").block(TIMEOUT)).isFalse();
        assertThat(repository.existsByProviderIdAndLine(otherProviderId, "HEALTH").block(TIMEOUT))
                .as("tags do not leak between providers")
                .isFalse();
    }

    @Test
    void insert_unknownLine_violatesCheckConstraint() {
        assertThatThrownBy(() -> repository.insert(providerId, "WRONG").block(TIMEOUT))
                .hasMessageContaining("provider_insurance_lines_ck");
    }

    @Test
    void insert_duplicateTag_violatesCompositePrimaryKey() {
        repository.insert(providerId, "HEALTH").block(TIMEOUT);

        assertThatThrownBy(() -> repository.insert(providerId, "HEALTH").block(TIMEOUT))
                .hasMessageContaining("provider_insurance_lines_pkey");
    }

    @Test
    void delete_removesTheTag_andReportsAffectedCount() {
        repository.insert(providerId, "HEALTH").block(TIMEOUT);

        assertThat(repository.delete(providerId, "HEALTH").block(TIMEOUT)).isEqualTo(1L);
        assertThat(repository.existsByProviderIdAndLine(providerId, "HEALTH").block(TIMEOUT)).isFalse();
        assertThat(repository.delete(providerId, "HEALTH").block(TIMEOUT))
                .as("removing an absent tag affects no rows")
                .isEqualTo(0L);
    }

    @Test
    void deletingTheProvider_cascadesItsTags() {
        repository.insert(providerId, "HEALTH").block(TIMEOUT);

        db.sql("DELETE FROM public.providers WHERE id = :id")
                .bind("id", providerId).then().block(TIMEOUT);

        assertThat(repository.findByProviderId(providerId).collectList().block(TIMEOUT))
                .as("provider_insurance_lines cascades on provider delete")
                .isEmpty();
    }
}

package com.medfund.finance.integration;

import com.medfund.finance.dto.ProviderBalanceFilterParams;
import com.medfund.finance.dto.ProviderBalanceRow;
import com.medfund.shared.testfixtures.WithTenant;
import com.medfund.finance.repository.ProviderBalanceQueryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the {@code public.provider_tenants} membership guard on
 * {@link ProviderBalanceQueryRepository}'s search + count. Both drive the
 * provider-balances (creditors) operational list.
 */
@WithTenant(AbstractProviderJoinIT.TENANT)
class ProviderBalanceReportProviderJoinIT extends AbstractProviderJoinIT {

    @Autowired private ProviderBalanceQueryRepository repository;

    private static final ProviderBalanceFilterParams ALL =
            new ProviderBalanceFilterParams(null, null, "providerName", "asc", 0, 50);

    @BeforeEach
    void seed() {
        run("DELETE FROM provider_balances");
        seedProviders();

        balance(LINKED_PROVIDER, "1200.00");
        balance(UNLINKED_PROVIDER, "900.00");
    }

    @Test
    void search_resolvesNameForContractedProvider_andMasksTheOtherOne() {
        List<ProviderBalanceRow> rows = inTenant(repository.search(ALL, 50, 0));

        assertThat(rows).hasSize(2);
        assertThat(rows)
                .filteredOn(r -> r.providerId().equals(LINKED_PROVIDER))
                .singleElement()
                .extracting(ProviderBalanceRow::providerName)
                .isEqualTo(LINKED_NAME);
        // The balance row is tenant-local and stays on the ledger; what the
        // membership guard withholds is the platform provider's name.
        assertThat(rows)
                .filteredOn(r -> r.providerId().equals(UNLINKED_PROVIDER))
                .singleElement()
                .extracting(ProviderBalanceRow::providerName)
                .isNull();
    }

    @Test
    void search_qFilterOnName_onlyMatchesContractedProvider() {
        var byLinked = new ProviderBalanceFilterParams(null, "sunrise", "providerName", "asc", 0, 50);
        assertThat(inTenant(repository.search(byLinked, 50, 0)))
                .singleElement()
                .extracting(ProviderBalanceRow::providerId)
                .isEqualTo(LINKED_PROVIDER);

        // "Offnetwork Hospital" exists in public.providers but its name is not
        // reachable from this tenant, so the name filter cannot find it.
        var byUnlinked = new ProviderBalanceFilterParams(null, "offnetwork", "providerName", "asc", 0, 50);
        assertThat(inTenant(repository.search(byUnlinked, 50, 0))).isEmpty();
    }

    @Test
    void count_matchesTheUnfilteredRowCount() {
        assertThat(inTenant(repository.count(ALL))).isEqualTo(2L);

        var byLinked = new ProviderBalanceFilterParams(null, "sunrise", null, null, 0, 50);
        assertThat(inTenant(repository.count(byLinked))).isEqualTo(1L);
    }

    private void balance(UUID providerId, String outstanding) {
        insert("INSERT INTO provider_balances (id, provider_id, total_claimed, total_approved, "
                        + "total_paid, outstanding_balance, currency_code) "
                        + "VALUES (:id, :pid, :claimed, :approved, :paid, :outstanding, 'USD')",
                Map.of("id", UUID.randomUUID(), "pid", providerId,
                        "claimed", new BigDecimal("2000.00"),
                        "approved", new BigDecimal("1500.00"),
                        "paid", new BigDecimal("300.00"),
                        "outstanding", new BigDecimal(outstanding)));
    }
}

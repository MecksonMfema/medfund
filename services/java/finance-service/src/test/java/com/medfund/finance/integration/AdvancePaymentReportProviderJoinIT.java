package com.medfund.finance.integration;

import com.medfund.finance.dto.AdvancePaymentFilterParams;
import com.medfund.finance.dto.AdvancePaymentRow;
import com.medfund.shared.testfixtures.WithTenant;
import com.medfund.finance.repository.AdvancePaymentQueryRepository;
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
 * {@link AdvancePaymentQueryRepository}'s search + count.
 */
@WithTenant(AbstractProviderJoinIT.TENANT)
class AdvancePaymentReportProviderJoinIT extends AbstractProviderJoinIT {

    @Autowired private AdvancePaymentQueryRepository repository;

    private static AdvancePaymentFilterParams filters(String q) {
        return new AdvancePaymentFilterParams(null, null, null, q, "providerName", "asc", 0, 50);
    }

    @BeforeEach
    void seed() {
        run("DELETE FROM advance_payments");
        seedProviders();

        advance(LINKED_PROVIDER, "ADV-1", "500.00");
        advance(UNLINKED_PROVIDER, "ADV-2", "250.00");
    }

    @Test
    void search_resolvesNameForContractedProvider_andMasksTheOtherOne() {
        List<AdvancePaymentRow> rows = inTenant(repository.search(filters(null), 50, 0));

        assertThat(rows).hasSize(2);
        assertThat(rows)
                .filteredOn(r -> r.providerId().equals(LINKED_PROVIDER))
                .singleElement()
                .extracting(AdvancePaymentRow::providerName)
                .isEqualTo(LINKED_NAME);
        assertThat(rows)
                .filteredOn(r -> r.providerId().equals(UNLINKED_PROVIDER))
                .singleElement()
                .extracting(AdvancePaymentRow::providerName)
                .isNull();
    }

    @Test
    void search_qFilterOnProviderName_onlyMatchesContractedProvider() {
        assertThat(inTenant(repository.search(filters("sunrise"), 50, 0)))
                .singleElement()
                .extracting(AdvancePaymentRow::reference)
                .isEqualTo("ADV-1");

        assertThat(inTenant(repository.search(filters("offnetwork"), 50, 0))).isEmpty();
    }

    @Test
    void count_runsUnderTheSameGuardedJoin() {
        assertThat(inTenant(repository.count(filters(null)))).isEqualTo(2L);
        assertThat(inTenant(repository.count(filters("sunrise")))).isEqualTo(1L);
    }

    private void advance(UUID providerId, String reference, String amount) {
        insert("INSERT INTO advance_payments (id, provider_id, amount, currency_code, "
                        + "payment_method, reference) "
                        + "VALUES (:id, :pid, :amount, 'USD', 'EFT', :ref)",
                Map.of("id", UUID.randomUUID(), "pid", providerId,
                        "amount", new BigDecimal(amount), "ref", reference));
    }
}

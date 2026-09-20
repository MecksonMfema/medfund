package com.medfund.finance.integration;

import com.medfund.finance.dto.PaymentAdviceFilterParams;
import com.medfund.finance.dto.PaymentAdviceRowResponse;
import com.medfund.shared.testfixtures.WithTenant;
import com.medfund.finance.repository.PaymentAdviceQueryRepository;
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
 * {@link PaymentAdviceQueryRepository}'s {@code BASE_SELECT}, count and
 * perCurrencyTotals: the {@code payee_name} COALESCE has to resolve the
 * provider name for a PROVIDER-typed advice, and fall through to blank when
 * the tenant holds no membership row for that provider.
 */
@WithTenant(AbstractProviderJoinIT.TENANT)
class PaymentAdviceReportProviderJoinIT extends AbstractProviderJoinIT {

    @Autowired private PaymentAdviceQueryRepository repository;

    private static PaymentAdviceFilterParams filters(String q) {
        return new PaymentAdviceFilterParams(null, null, null, null, null, null, null, null,
                q, "adviceNumber", "asc", 0, 50);
    }

    @BeforeEach
    void seed() {
        run("DELETE FROM payment_advices");
        seedProviders();

        advice("PA-0001", LINKED_PROVIDER, "1000.00");
        advice("PA-0002", UNLINKED_PROVIDER, "400.00");
    }

    @Test
    void search_payeeNameResolvesForContractedProvider_andFallsThroughToBlank() {
        List<PaymentAdviceRowResponse> rows = inTenant(repository.search(filters(null), 50, 0));

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).adviceNumber()).isEqualTo("PA-0001");
        assertThat(rows.get(0).payeeName()).isEqualTo(LINKED_NAME);
        // COALESCE(provider name, member name, '') — with the provider name
        // withheld and no member on the advice, the last arm wins.
        assertThat(rows.get(1).adviceNumber()).isEqualTo("PA-0002");
        assertThat(rows.get(1).payeeName()).isEmpty();
    }

    @Test
    void search_qFilterOnPayeeName_onlyMatchesContractedProvider() {
        assertThat(inTenant(repository.search(filters("sunrise"), 50, 0)))
                .singleElement()
                .extracting(PaymentAdviceRowResponse::adviceNumber)
                .isEqualTo("PA-0001");

        assertThat(inTenant(repository.search(filters("offnetwork"), 50, 0))).isEmpty();
    }

    @Test
    void countAndPerCurrencyTotals_runUnderTheSameGuardedJoin() {
        assertThat(inTenant(repository.count(filters(null)))).isEqualTo(2L);
        assertThat(inTenant(repository.count(filters("sunrise")))).isEqualTo(1L);

        var perCurrency = inTenant(repository.perCurrencyTotals(filters(null)));
        assertThat(perCurrency).containsKey("USD");
        assertThat(perCurrency.get("USD").totalAmount())
                .isEqualByComparingTo(new BigDecimal("1400.00"));
        assertThat(perCurrency.get("USD").rowCount()).isEqualTo(2L);
    }

    private void advice(String number, UUID providerId, String netDue) {
        insert("INSERT INTO payment_advices (id, advice_number, payee_type, provider_id, "
                        + "currency_code, total_amount, claim_count, net_due_amount) "
                        + "VALUES (:id, :num, 'PROVIDER', :pid, 'USD', :total, 1, :netDue)",
                Map.of("id", UUID.randomUUID(), "num", number, "pid", providerId,
                        "total", new BigDecimal(netDue), "netDue", new BigDecimal(netDue)));
    }
}

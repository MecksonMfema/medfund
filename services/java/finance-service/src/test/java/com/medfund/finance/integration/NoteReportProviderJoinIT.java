package com.medfund.finance.integration;

import com.medfund.finance.dto.NoteFilterParams;
import com.medfund.finance.dto.NoteRow;
import com.medfund.shared.testfixtures.WithTenant;
import com.medfund.finance.repository.NoteQueryRepository;
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
 * {@link NoteQueryRepository}'s search + count + perCurrencyTotals, which
 * all share one {@code baseFrom()} join.
 */
@WithTenant(AbstractProviderJoinIT.TENANT)
class NoteReportProviderJoinIT extends AbstractProviderJoinIT {

    @Autowired private NoteQueryRepository repository;

    private static NoteFilterParams filters(String q) {
        return new NoteFilterParams(null, null, null, null, null, null, q,
                "noteNumber", "asc", 0, 50);
    }

    @BeforeEach
    void seed() {
        run("DELETE FROM notes");
        seedProviders();

        note("N-0001", LINKED_PROVIDER, "120.00");
        note("N-0002", UNLINKED_PROVIDER, "80.00");
    }

    @Test
    void search_resolvesNameForContractedProvider_andMasksTheOtherOne() {
        List<NoteRow> rows = inTenant(repository.search(filters(null), 50, 0));

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).noteNumber()).isEqualTo("N-0001");
        assertThat(rows.get(0).providerName()).isEqualTo(LINKED_NAME);
        // The note itself is tenant-local and stays on the list; only the
        // platform provider's name is withheld.
        assertThat(rows.get(1).noteNumber()).isEqualTo("N-0002");
        assertThat(rows.get(1).providerName()).isNull();
    }

    @Test
    void search_qFilterOnProviderName_onlyMatchesContractedProvider() {
        assertThat(inTenant(repository.search(filters("sunrise"), 50, 0)))
                .singleElement()
                .extracting(NoteRow::noteNumber)
                .isEqualTo("N-0001");

        assertThat(inTenant(repository.search(filters("offnetwork"), 50, 0))).isEmpty();
    }

    @Test
    void countAndPerCurrencyTotals_runUnderTheSameGuardedJoin() {
        assertThat(inTenant(repository.count(filters(null)))).isEqualTo(2L);
        assertThat(inTenant(repository.count(filters("sunrise")))).isEqualTo(1L);

        var perCurrency = inTenant(repository.perCurrencyTotals(filters(null)));
        assertThat(perCurrency).containsKey("USD");
        assertThat(perCurrency.get("USD").totalAmount())
                .isEqualByComparingTo(new BigDecimal("200.00"));
        assertThat(perCurrency.get("USD").rowCount()).isEqualTo(2L);
    }

    private void note(String number, UUID providerId, String amount) {
        insert("INSERT INTO notes (id, note_number, provider_id, direction, note_type, amount, "
                        + "currency_code, status) "
                        + "VALUES (:id, :num, :pid, 'DEBIT', 'TAX_WITHHELD', :amount, 'USD', 'posted')",
                Map.of("id", UUID.randomUUID(), "num", number, "pid", providerId,
                        "amount", new BigDecimal(amount)));
    }
}

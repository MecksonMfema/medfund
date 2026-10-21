package com.medfund.finance.integration;

import com.medfund.finance.dto.CreditorFilterParams;
import com.medfund.finance.dto.CreditorRow;
import com.medfund.shared.testfixtures.WithTenant;
import com.medfund.finance.repository.CreditorQueryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers {@code CreditorQueryRepository.providerBranch}: the membership
 * guard, and the column fix behind it. {@code public.providers} renamed
 * {@code practice_number} to {@code registration_number} in public/V106,
 * so the creditors list had been projecting and filtering on a column that
 * no longer exists on the platform table. Nothing tested this before.
 *
 * <p>The member half is exercised too, because it shares every bind with
 * the provider half and the {@code :tenantId} bind is applied only when the
 * provider branch is part of the union.
 */
@WithTenant(AbstractProviderJoinIT.TENANT)
class CreditorReportProviderJoinIT extends AbstractProviderJoinIT {

    @Autowired private CreditorQueryRepository repository;

    private static final UUID MEMBER_A = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    private static CreditorFilterParams filters(String subjectType, String q) {
        return new CreditorFilterParams(subjectType, null, q, "subjectName", "asc", 0, 50);
    }

    @BeforeEach
    void seed() {
        run("DELETE FROM provider_balances");
        run("DELETE FROM member_balances");
        run("DELETE FROM members");
        seedProviders();

        providerBalance(LINKED_PROVIDER, "1200.00");
        providerBalance(UNLINKED_PROVIDER, "900.00");

        insert("INSERT INTO members (id, first_name, last_name, member_number) "
                        + "VALUES (:id, :fn, :ln, :mn)",
                Map.of("id", MEMBER_A, "fn", "Ada", "ln", "Lovelace", "mn", "M-0001"));
        insert("INSERT INTO member_balances (id, member_id, outstanding_balance, currency_code) "
                        + "VALUES (:id, :mid, :outstanding, 'USD')",
                Map.of("id", UUID.randomUUID(), "mid", MEMBER_A,
                        "outstanding", new BigDecimal("150.00")));
    }

    @Test
    void providerBranch_projectsRegistrationNumberAsSubjectCode() {
        List<CreditorRow> rows = inTenant(repository.search(filters("PROVIDER", null), 50, 0));

        assertThat(rows).hasSize(2);
        assertThat(rows)
                .filteredOn(r -> r.subjectId().equals(LINKED_PROVIDER))
                .singleElement()
                .satisfies(r -> {
                    assertThat(r.subjectCode()).isEqualTo(LINKED_REG);
                    assertThat(r.subjectName()).isEqualTo(LINKED_NAME);
                    assertThat(r.subjectEmail()).isEqualTo("billing@sunrise.test");
                });
    }

    @Test
    void providerBranch_masksTheProviderThisTenantHasNoContractWith() {
        assertThat(inTenant(repository.search(filters("PROVIDER", null), 50, 0)))
                .filteredOn(r -> r.subjectId().equals(UNLINKED_PROVIDER))
                .singleElement()
                .satisfies(r -> {
                    assertThat(r.subjectName()).isNull();
                    assertThat(r.subjectCode()).isNull();
                    assertThat(r.subjectEmail()).isNull();
                });
    }

    @Test
    void qFilter_matchesRegistrationNumber() {
        assertThat(inTenant(repository.search(filters("PROVIDER", "reg-1001"), 50, 0)))
                .singleElement()
                .extracting(CreditorRow::subjectId)
                .isEqualTo(LINKED_PROVIDER);

        // Same shape of code on the unlinked provider: unreachable from here.
        assertThat(inTenant(repository.search(filters("PROVIDER", "reg-2002"), 50, 0))).isEmpty();
    }

    @Test
    void bothBranches_unionCountsAndPerCurrencyTotalsStayConsistent() {
        assertThat(inTenant(repository.count(filters("BOTH", null)))).isEqualTo(3L);
        assertThat(inTenant(repository.count(filters("MEMBER", null)))).isEqualTo(1L);

        var perCurrency = inTenant(repository.perCurrencyTotals(filters("BOTH", null)));
        assertThat(perCurrency).containsKey("USD");
        assertThat(perCurrency.get("USD").totalAmount())
                .isEqualByComparingTo(new BigDecimal("2250.00"));
        assertThat(perCurrency.get("USD").rowCount()).isEqualTo(3L);
    }

    private void providerBalance(UUID providerId, String outstanding) {
        insert("INSERT INTO provider_balances (id, provider_id, outstanding_balance, currency_code) "
                        + "VALUES (:id, :pid, :outstanding, 'USD')",
                Map.of("id", UUID.randomUUID(), "pid", providerId,
                        "outstanding", new BigDecimal(outstanding)));
    }
}

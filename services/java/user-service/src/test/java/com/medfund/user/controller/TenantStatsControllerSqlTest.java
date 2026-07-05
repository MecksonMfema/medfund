package com.medfund.user.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.RowsFetchSpec;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the SQL that drives the tenant dashboard's Billing tab. Two
 * invariants that would silently drift on any refactor:
 *
 * <ul>
 *   <li><b>Received-payments source</b> reads from the transactions
 *       ledger, not from {@code contributions.status='paid'}. A cash
 *       payment posted without a linked contribution still moves real
 *       money and must show up on the dashboard — reading the derivative
 *       contribution status misses it. The pivot lives across four SQL
 *       blocks; this test captures every {@code db.sql} call the
 *       controller emits and asserts the WHERE clauses are present.</li>
 *   <li><b>invoicesOutstanding</b> is a count over the invoices table
 *       ({@code status IN ('issued','overdue')}), not a contributions
 *       count. Regression here would repopulate the Payments Requested
 *       card with the old (wrong) metric.</li>
 * </ul>
 *
 * <p>Deliberately a string-level assertion. A full Testcontainers IT
 * for this controller would need ~10 tables seeded with realistic
 * fixture data; that's overkill for a pivot that shows up as a
 * substring drift. The chart+trend endpoint variants use the same
 * pivot and are exercised here too.
 */
@ExtendWith(MockitoExtension.class)
class TenantStatsControllerSqlTest {

    @Mock DatabaseClient db;

    private ArgumentCaptor<String> sqlCaptor;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        sqlCaptor = ArgumentCaptor.forClass(String.class);
        DatabaseClient.GenericExecuteSpec spec = mock(DatabaseClient.GenericExecuteSpec.class);
        RowsFetchSpec fetch = mock(RowsFetchSpec.class);
        lenient().when(db.sql(anyString())).thenReturn(spec);
        lenient().when(spec.bind(anyString(), any())).thenReturn(spec);
        lenient().when(spec.map(any(Function.class))).thenAnswer(inv -> fetch);
        // Non-empty schema lookup so buildStats actually fires. All the
        // returned "value" needs to satisfy is being non-null — the
        // Mockito-stubbed .map bypass discards the actual row mapper so
        // downstream type mismatches don't hit until Mono.zip's terminal
        // .map. We block below with a caught error and read the captor
        // regardless — by that point every db.sql() call has already
        // fired eagerly at chain assembly time.
        lenient().when(fetch.one()).thenReturn(Mono.just("test_schema"));
        lenient().when(fetch.all()).thenReturn(Flux.empty());
    }

    @Test
    void stats_receivedPayments_readFromTransactionsPayments_notContributionsPaid() {
        // Simulate a normal /tenant-stats hit — the reactive chain will
        // error on the downstream .map's type mismatch but that's after
        // every db.sql call has been captured.
        TenantStatsController ctrl = new TenantStatsController(db);
        try {
            ctrl.getStats("11111111-1111-1111-1111-111111111111")
                    .block(java.time.Duration.ofSeconds(2));
        } catch (Exception ignored) { /* expected — see comment above */ }

        List<String> sqls = collectAllSql();

        // ── Received-payments amounts pivot ──────────────────────────
        // billingCounts contains the blended (single-value) SUMs for
        // this month / this year. Both must read from the transactions
        // ledger with transaction_type='PAYMENT' AND status='completed'.
        assertThat(hasSubstring(sqls,
                ".transactions",
                "transaction_type = 'PAYMENT'",
                "status = 'completed'"))
                .as("billingCounts must SUM from transactions where transaction_type='PAYMENT' AND status='completed'")
                .isTrue();

        // Regression floor: the old contributions-based query must not
        // resurface. A silent revert would keep the metric ~correct in
        // ordinary flows but drop cash payments that never linked to
        // a contribution row.
        assertThat(sqls)
                .as("Old contributions-based SUM must not resurface anywhere")
                .noneMatch(s -> s.contains(".contributions WHERE status = 'paid'"));
    }

    @Test
    void stats_perCurrency_receivedPayments_readFromTransactionsPayments() {
        TenantStatsController ctrl = new TenantStatsController(db);
        try {
            ctrl.getStats("11111111-1111-1111-1111-111111111111")
                    .block(java.time.Duration.ofSeconds(2));
        } catch (Exception ignored) { }

        List<String> sqls = collectAllSql();

        // sumByCurrency emits: SELECT currency_code, COALESCE(SUM(amount),0)
        //                       FROM "<schema>".<table>
        //                       WHERE <predicate>
        // for both this-month and this-year over transactions.
        assertThat(hasSubstring(sqls,
                "SELECT currency_code",
                ".transactions",
                "transaction_type = 'PAYMENT'",
                "status = 'completed'"))
                .as("Per-currency received-payments SUM must use transactions ledger")
                .isTrue();
    }

    @Test
    void stats_invoicesOutstanding_countsInvoicesAtIssuedOrOverdue() {
        TenantStatsController ctrl = new TenantStatsController(db);
        try {
            ctrl.getStats("11111111-1111-1111-1111-111111111111")
                    .block(java.time.Duration.ofSeconds(2));
        } catch (Exception ignored) { }

        List<String> sqls = collectAllSql();

        // The Payments Requested card is COUNT of invoices at status
        // in ('issued','overdue'). Guards the semantic — contributions
        // are line items, not requests; the operator wants the invoice
        // count.
        assertThat(hasSubstring(sqls,
                "SELECT COUNT(*) FROM \"", ".invoices",
                "status IN ('issued','overdue')"))
                .as("invoicesOutstanding must COUNT invoices where status IN ('issued','overdue')")
                .isTrue();
    }

    @Test
    void charts_receivedPayments_trends_readFromTransactionsPayments() {
        // The Billing tab chart + the blended trend line both need to
        // pivot to the transactions ledger. Same reason as the stats
        // endpoint — a cash payment without a contribution link would
        // otherwise never appear on the trend.
        TenantStatsController ctrl = new TenantStatsController(db);
        try {
            ctrl.getCharts("11111111-1111-1111-1111-111111111111", "month")
                    .block(java.time.Duration.ofSeconds(2));
        } catch (Exception ignored) { }

        List<String> sqls = collectAllSql();

        // Blended trend (contributionsTrend): LEFT JOIN transactions tx
        assertThat(hasSubstring(sqls,
                ".transactions tx",
                "tx.transaction_type = 'PAYMENT'",
                "tx.status = 'completed'"))
                .as("Blended received-payments trend must LEFT JOIN transactions with type='PAYMENT'")
                .isTrue();

        // Regression floor for the trend side too.
        assertThat(sqls)
                .as("Old contributions-status='paid' trend join must not resurface")
                .noneMatch(s -> s.contains(".contributions c")
                             && s.contains("c.status = 'paid'"));
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private List<String> collectAllSql() {
        // Every db.sql(..) call the controller made lives in the captor.
        // Re-verify with any() so we harvest without failing on 0 calls
        // (the assertions below carry the semantic weight).
        org.mockito.Mockito.verify(db, org.mockito.Mockito.atLeastOnce()).sql(sqlCaptor.capture());
        return sqlCaptor.getAllValues();
    }

    /** True iff at least one captured SQL string contains every needle. */
    private static boolean hasSubstring(List<String> haystack, String... needles) {
        return haystack.stream().anyMatch(s -> {
            for (String n : needles) if (!s.contains(n)) return false;
            return true;
        });
    }
}

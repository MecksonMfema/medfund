package com.medfund.finance.service;

import com.medfund.finance.client.ContributionsClient;
import com.medfund.finance.dto.CollectionRateReportResponse;
import com.medfund.finance.dto.CollectionRateReportResponse.DimensionRow;
import com.medfund.shared.report.MonthlyAggregateRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CollectionRateReportService}. Guards the three
 * behaviours the report page depends on:
 *
 * <ol>
 *   <li>Happy path: both peer calls succeed → per-dimension rows carry
 *       correct billed / received / rate totals.</li>
 *   <li>Peer-down: one call fails → warnings list populated, report
 *       still succeeds with partial data (invariant #7).</li>
 *   <li>Per-currency isolation: never mixes currencies (G34).</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class CollectionRateReportServiceTest {

    @Mock private ContributionsClient contributionsClient;
    @InjectMocks private CollectionRateReportService service;

    private final LocalDate periodStart = LocalDate.of(2026, 7, 1);
    private final LocalDate periodEnd   = LocalDate.of(2026, 8, 31);
    private final UUID schemeGold = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void compute_happyPath_producesPerDimensionRatesPerCurrency() {
        stubAll(
                List.of(billing(schemeGold, "Gold", "USD", LocalDate.of(2026, 7, 1), "100.00"),
                        billing(schemeGold, "Gold", "USD", LocalDate.of(2026, 8, 1), "100.00")),
                List.of(), List.of(),
                List.of(receipt(schemeGold, "Gold", "USD", LocalDate.of(2026, 7, 1), "50.00"),
                        receipt(schemeGold, "Gold", "USD", LocalDate.of(2026, 8, 1), "80.00")),
                List.of(), List.of());

        List<String> warnings = new ArrayList<>();
        StepVerifier.create(service.compute(periodStart, periodEnd, warnings))
                .assertNext(report -> {
                    assertThat(report.byScheme()).hasSize(1);
                    DimensionRow row = report.byScheme().get(0);
                    assertThat(row.dimensionId()).isEqualTo(schemeGold);
                    assertThat(row.totalBilled()).isEqualByComparingTo("200.00");
                    assertThat(row.totalReceived()).isEqualByComparingTo("130.00");
                    assertThat(row.totalRatePct()).isEqualByComparingTo("65.00");
                    assertThat(row.monthlyBuckets()).hasSize(2);
                    assertThat(row.monthlyBuckets().get(0).ratePct()).isEqualByComparingTo("50.00");
                    assertThat(row.monthlyBuckets().get(1).ratePct()).isEqualByComparingTo("80.00");
                })
                .verifyComplete();
        assertThat(warnings).isEmpty();
    }

    @Test
    void compute_receiptsPeerDown_returnsPartialDataWithWarnings() {
        // Billing succeeds; every receipts call errors → warnings populate,
        // rate reads 0% because no receipts came back — treasurer sees the
        // warning banner and the partial billing-only picture.
        when(contributionsClient.aggregateBillingMonthly(any(), any(), any()))
                .thenReturn(Mono.just(List.of(
                        billing(schemeGold, "Gold", "USD", LocalDate.of(2026, 7, 1), "100.00"))));
        when(contributionsClient.aggregateReceiptsMonthly(any(), any(), any()))
                .thenReturn(Mono.error(new RuntimeException("connection refused")));

        List<String> warnings = new ArrayList<>();
        StepVerifier.create(service.compute(periodStart, periodEnd, warnings))
                .assertNext(report -> {
                    assertThat(report.byScheme()).hasSize(1);
                    DimensionRow row = report.byScheme().get(0);
                    assertThat(row.totalBilled()).isEqualByComparingTo("100.00");
                    assertThat(row.totalReceived()).isEqualByComparingTo("0");
                    assertThat(row.totalRatePct()).isEqualByComparingTo("0.00");
                })
                .verifyComplete();
        // Three receipts calls (SCHEME, GROUP, MEMBER) all fail → three warnings.
        assertThat(warnings).hasSize(3);
        assertThat(warnings).allMatch(w -> w.contains("receipts-aggregate-monthly"));
    }

    @Test
    void compute_zeroBilling_producesNullRateNotDivideByZero() {
        // Zero billed with some received (e.g., a refund cycle) — rate should
        // read null, not blow up with an ArithmeticException.
        stubAll(List.of(), List.of(), List.of(),
                List.of(receipt(schemeGold, "Gold", "USD", LocalDate.of(2026, 7, 1), "50.00")),
                List.of(), List.of());

        StepVerifier.create(service.compute(periodStart, periodEnd, new ArrayList<>()))
                .assertNext(report -> {
                    DimensionRow row = report.byScheme().get(0);
                    assertThat(row.totalBilled()).isEqualByComparingTo("0");
                    assertThat(row.totalReceived()).isEqualByComparingTo("50.00");
                    assertThat(row.totalRatePct()).isNull();
                    assertThat(row.monthlyBuckets().get(0).ratePct()).isNull();
                })
                .verifyComplete();
    }

    @Test
    void compose_neverMixesCurrencies() {
        // Same scheme, two currencies → two distinct DimensionRows.
        List<MonthlyAggregateRow> billing = List.of(
                billing(schemeGold, "Gold", "USD", LocalDate.of(2026, 7, 1), "100.00"),
                billing(schemeGold, "Gold", "ZWL", LocalDate.of(2026, 7, 1), "36500.00"));
        List<MonthlyAggregateRow> receipts = List.of(
                receipt(schemeGold, "Gold", "USD", LocalDate.of(2026, 7, 1), "50.00"),
                receipt(schemeGold, "Gold", "ZWL", LocalDate.of(2026, 7, 1), "20000.00"));

        List<DimensionRow> rows = CollectionRateReportService.compose(billing, receipts);
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(DimensionRow::currencyCode).containsExactlyInAnyOrder("USD", "ZWL");
        DimensionRow usd = rows.stream().filter(r -> "USD".equals(r.currencyCode())).findFirst().orElseThrow();
        DimensionRow zwl = rows.stream().filter(r -> "ZWL".equals(r.currencyCode())).findFirst().orElseThrow();
        assertThat(usd.totalRatePct()).isEqualByComparingTo("50.00");
        assertThat(zwl.totalRatePct()).isEqualByComparingTo("54.79"); // 20000/36500 * 100 = 54.7945
    }

    @Test
    void ratePercent_zeroDenominator_returnsNull() {
        assertThat(CollectionRateReportService.ratePercent(new BigDecimal("50"), BigDecimal.ZERO)).isNull();
        assertThat(CollectionRateReportService.ratePercent(null, BigDecimal.ZERO)).isNull();
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private void stubAll(List<MonthlyAggregateRow> schemeBilling,
                         List<MonthlyAggregateRow> groupBilling,
                         List<MonthlyAggregateRow> memberBilling,
                         List<MonthlyAggregateRow> schemeReceipts,
                         List<MonthlyAggregateRow> groupReceipts,
                         List<MonthlyAggregateRow> memberReceipts) {
        when(contributionsClient.aggregateBillingMonthly(any(), any(), eq("SCHEME")))
                .thenReturn(Mono.just(schemeBilling));
        when(contributionsClient.aggregateBillingMonthly(any(), any(), eq("GROUP")))
                .thenReturn(Mono.just(groupBilling));
        when(contributionsClient.aggregateBillingMonthly(any(), any(), eq("MEMBER")))
                .thenReturn(Mono.just(memberBilling));
        when(contributionsClient.aggregateReceiptsMonthly(any(), any(), eq("SCHEME")))
                .thenReturn(Mono.just(schemeReceipts));
        when(contributionsClient.aggregateReceiptsMonthly(any(), any(), eq("GROUP")))
                .thenReturn(Mono.just(groupReceipts));
        when(contributionsClient.aggregateReceiptsMonthly(any(), any(), eq("MEMBER")))
                .thenReturn(Mono.just(memberReceipts));
    }

    private static MonthlyAggregateRow billing(UUID id, String name, String ccy, LocalDate month, String amount) {
        return new MonthlyAggregateRow("SCHEME", id, name, ccy, month, new BigDecimal(amount));
    }

    private static MonthlyAggregateRow receipt(UUID id, String name, String ccy, LocalDate month, String amount) {
        return new MonthlyAggregateRow("SCHEME", id, name, ccy, month, new BigDecimal(amount));
    }
}

package com.medfund.finance.service;

import com.medfund.finance.client.ClaimsClient;
import com.medfund.finance.client.ContributionsClient;
import com.medfund.finance.dto.BillingAggregateRow;
import com.medfund.finance.dto.ClaimsAggregateRow;
import com.medfund.finance.dto.LossRatioReportResponse.LossRatioRow;
import com.medfund.finance.dto.MemberPaymentsReportResponse.MemberPaymentRow;
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
 * Unit tests for {@link CrossServiceReportService}. Guards the behaviours
 * both Phase 5 reports depend on:
 *
 * <ol>
 *   <li>Loss-ratio happy path: billed + funnel compose per (scheme,
 *       currency) with the derived ratio + billed-minus-paid.</li>
 *   <li>Zero-denominator → {@code paidRatioPct} null, no division by zero.</li>
 *   <li>Per-currency isolation: never mixes currencies (G34).</li>
 *   <li>Peer-down → warnings populated, report still succeeds with partial
 *       data (G37 / invariant #7).</li>
 *   <li>Member-payments: month buckets summed across the reporting window,
 *       net position = received − claims paid.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class CrossServiceReportServiceTest {

    @Mock private ContributionsClient contributionsClient;
    @Mock private ClaimsClient claimsClient;
    @InjectMocks private CrossServiceReportService service;

    private final LocalDate periodStart = LocalDate.of(2026, 7, 1);
    private final LocalDate periodEnd   = LocalDate.of(2026, 8, 31);
    private final UUID schemeGold = UUID.fromString("11111111-1111-1111-1111-111111111111");

    // ── Loss ratio ──────────────────────────────────────────────────────

    @Test
    void lossRatio_happyPath_composesFunnelAndRatio() {
        when(contributionsClient.aggregateBilling(any(), any()))
                .thenReturn(Mono.just(List.of(
                        billing(schemeGold, "Gold", "USD", "200.00"))));
        when(claimsClient.aggregateClaims(any(), any()))
                .thenReturn(Mono.just(List.of(
                        claims(schemeGold, "Gold", "USD", "150.00", "120.00", "100.00"))));

        List<String> warnings = new ArrayList<>();
        StepVerifier.create(service.lossRatio(periodStart, periodEnd, warnings))
                .assertNext(report -> {
                    assertThat(report.periodStart()).isEqualTo(periodStart);
                    assertThat(report.periodEnd()).isEqualTo(periodEnd);
                    assertThat(report.rows()).hasSize(1);
                    LossRatioRow row = report.rows().get(0);
                    assertThat(row.schemeId()).isEqualTo(schemeGold);
                    assertThat(row.totalBilled()).isEqualByComparingTo("200.00");
                    assertThat(row.totalClaimed()).isEqualByComparingTo("150.00");
                    assertThat(row.totalApproved()).isEqualByComparingTo("120.00");
                    assertThat(row.totalPaid()).isEqualByComparingTo("100.00");
                    assertThat(row.paidRatioPct()).isEqualByComparingTo("50.00"); // 100/200
                    assertThat(row.billedMinusPaid()).isEqualByComparingTo("100.00");
                })
                .verifyComplete();
        assertThat(warnings).isEmpty();
    }

    @Test
    void lossRatio_zeroBilling_producesNullRatioNotDivideByZero() {
        when(contributionsClient.aggregateBilling(any(), any()))
                .thenReturn(Mono.just(List.of()));
        when(claimsClient.aggregateClaims(any(), any()))
                .thenReturn(Mono.just(List.of(
                        claims(schemeGold, "Gold", "USD", "50.00", "40.00", "30.00"))));

        StepVerifier.create(service.lossRatio(periodStart, periodEnd, new ArrayList<>()))
                .assertNext(report -> {
                    LossRatioRow row = report.rows().get(0);
                    assertThat(row.totalBilled()).isEqualByComparingTo("0");
                    assertThat(row.totalPaid()).isEqualByComparingTo("30.00");
                    assertThat(row.paidRatioPct()).isNull();
                    assertThat(row.billedMinusPaid()).isEqualByComparingTo("-30.00");
                })
                .verifyComplete();
    }

    @Test
    void lossRatio_claimsPeerDown_returnsPartialDataWithWarnings() {
        when(contributionsClient.aggregateBilling(any(), any()))
                .thenReturn(Mono.just(List.of(
                        billing(schemeGold, "Gold", "USD", "200.00"))));
        when(claimsClient.aggregateClaims(any(), any()))
                .thenReturn(Mono.error(new RuntimeException("connection refused")));

        List<String> warnings = new ArrayList<>();
        StepVerifier.create(service.lossRatio(periodStart, periodEnd, warnings))
                .assertNext(report -> {
                    assertThat(report.rows()).hasSize(1);
                    LossRatioRow row = report.rows().get(0);
                    assertThat(row.totalBilled()).isEqualByComparingTo("200.00");
                    assertThat(row.totalPaid()).isEqualByComparingTo("0");
                    assertThat(row.paidRatioPct()).isEqualByComparingTo("0.00");
                })
                .verifyComplete();
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0)).contains("claims-aggregate[SCHEME]");
    }

    @Test
    void composeLossRatio_neverMixesCurrencies() {
        List<BillingAggregateRow> billing = List.of(
                billing(schemeGold, "Gold", "USD", "100.00"),
                billing(schemeGold, "Gold", "ZWL", "36500.00"));
        List<ClaimsAggregateRow> claims = List.of(
                claims(schemeGold, "Gold", "USD", "90.00", "80.00", "50.00"),
                claims(schemeGold, "Gold", "ZWL", "30000.00", "20000.00", "10000.00"));

        List<LossRatioRow> rows = CrossServiceReportService.composeLossRatio(billing, claims);
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(LossRatioRow::currencyCode).containsExactlyInAnyOrder("USD", "ZWL");
        LossRatioRow usd = rows.stream().filter(r -> "USD".equals(r.currencyCode())).findFirst().orElseThrow();
        LossRatioRow zwl = rows.stream().filter(r -> "ZWL".equals(r.currencyCode())).findFirst().orElseThrow();
        assertThat(usd.paidRatioPct()).isEqualByComparingTo("50.00");
        assertThat(zwl.paidRatioPct()).isEqualByComparingTo("27.40"); // 10000/36500 * 100 = 27.3972
    }

    // ── Member payments ─────────────────────────────────────────────────

    @Test
    void memberPayments_happyPath_sumsBucketsAcrossPeriod() {
        UUID member = UUID.fromString("22222222-2222-2222-2222-222222222222");
        when(contributionsClient.aggregateBillingMonthly(any(), any(), eq("MEMBER")))
                .thenReturn(Mono.just(List.of(
                        monthly(member, "Ada", "USD", LocalDate.of(2026, 7, 1), "100.00"),
                        monthly(member, "Ada", "USD", LocalDate.of(2026, 8, 1), "100.00"))));
        when(contributionsClient.aggregateReceiptsMonthly(any(), any(), eq("MEMBER")))
                .thenReturn(Mono.just(List.of(
                        monthly(member, "Ada", "USD", LocalDate.of(2026, 7, 1), "50.00"),
                        monthly(member, "Ada", "USD", LocalDate.of(2026, 8, 1), "80.00"))));
        when(claimsClient.aggregateClaimsMonthly(any(), any(), eq("MEMBER")))
                .thenReturn(Mono.just(List.of(
                        monthly(member, "Ada", "USD", LocalDate.of(2026, 7, 1), "30.00"),
                        monthly(member, "Ada", "USD", LocalDate.of(2026, 8, 1), "40.00"))));

        List<String> warnings = new ArrayList<>();
        StepVerifier.create(service.memberPayments(periodStart, periodEnd, warnings))
                .assertNext(report -> {
                    assertThat(report.rows()).hasSize(1);
                    MemberPaymentRow row = report.rows().get(0);
                    assertThat(row.memberId()).isEqualTo(member);
                    assertThat(row.totalBilled()).isEqualByComparingTo("200.00");
                    assertThat(row.totalReceived()).isEqualByComparingTo("130.00");
                    assertThat(row.totalClaimsPaid()).isEqualByComparingTo("70.00");
                    assertThat(row.netPosition()).isEqualByComparingTo("60.00"); // 130 − 70
                })
                .verifyComplete();
        assertThat(warnings).isEmpty();
    }

    @Test
    void memberPayments_receiptsPeerDown_returnsPartialDataWithWarnings() {
        UUID member = UUID.fromString("22222222-2222-2222-2222-222222222222");
        when(contributionsClient.aggregateBillingMonthly(any(), any(), eq("MEMBER")))
                .thenReturn(Mono.just(List.of(
                        monthly(member, "Ada", "USD", LocalDate.of(2026, 7, 1), "100.00"))));
        when(contributionsClient.aggregateReceiptsMonthly(any(), any(), eq("MEMBER")))
                .thenReturn(Mono.error(new RuntimeException("connection refused")));
        when(claimsClient.aggregateClaimsMonthly(any(), any(), eq("MEMBER")))
                .thenReturn(Mono.just(List.of(
                        monthly(member, "Ada", "USD", LocalDate.of(2026, 7, 1), "40.00"))));

        List<String> warnings = new ArrayList<>();
        StepVerifier.create(service.memberPayments(periodStart, periodEnd, warnings))
                .assertNext(report -> {
                    assertThat(report.rows()).hasSize(1);
                    MemberPaymentRow row = report.rows().get(0);
                    assertThat(row.totalBilled()).isEqualByComparingTo("100.00");
                    assertThat(row.totalReceived()).isEqualByComparingTo("0");
                    assertThat(row.totalClaimsPaid()).isEqualByComparingTo("40.00");
                    assertThat(row.netPosition()).isEqualByComparingTo("-40.00");
                })
                .verifyComplete();
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0)).contains("receipts-aggregate-monthly[MEMBER]");
    }

    @Test
    void composeMemberPayments_neverMixesCurrencies() {
        UUID member = UUID.fromString("22222222-2222-2222-2222-222222222222");
        List<MonthlyAggregateRow> billing = List.of(
                monthly(member, "Ada", "USD", LocalDate.of(2026, 7, 1), "100.00"),
                monthly(member, "Ada", "ZWL", LocalDate.of(2026, 7, 1), "36500.00"));
        List<MonthlyAggregateRow> receipts = List.of(
                monthly(member, "Ada", "USD", LocalDate.of(2026, 7, 1), "50.00"),
                monthly(member, "Ada", "ZWL", LocalDate.of(2026, 7, 1), "20000.00"));
        List<MonthlyAggregateRow> claims = List.of(
                monthly(member, "Ada", "USD", LocalDate.of(2026, 7, 1), "30.00"),
                monthly(member, "Ada", "ZWL", LocalDate.of(2026, 7, 1), "5000.00"));

        List<MemberPaymentRow> rows = CrossServiceReportService.composeMemberPayments(billing, receipts, claims);
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(MemberPaymentRow::currencyCode).containsExactlyInAnyOrder("USD", "ZWL");
        MemberPaymentRow usd = rows.stream().filter(r -> "USD".equals(r.currencyCode())).findFirst().orElseThrow();
        MemberPaymentRow zwl = rows.stream().filter(r -> "ZWL".equals(r.currencyCode())).findFirst().orElseThrow();
        assertThat(usd.netPosition()).isEqualByComparingTo("20.00");
        assertThat(zwl.netPosition()).isEqualByComparingTo("15000.00");
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static BillingAggregateRow billing(UUID id, String name, String ccy, String amount) {
        return new BillingAggregateRow(id, name, ccy, new BigDecimal(amount));
    }

    private static ClaimsAggregateRow claims(UUID id, String name, String ccy,
                                             String claimed, String approved, String paid) {
        return new ClaimsAggregateRow("SCHEME", id, name, ccy,
                new BigDecimal(claimed), new BigDecimal(approved), new BigDecimal(paid));
    }

    private static MonthlyAggregateRow monthly(UUID id, String name, String ccy,
                                               LocalDate month, String amount) {
        return new MonthlyAggregateRow("MEMBER", id, name, ccy, month, new BigDecimal(amount));
    }
}

package com.medfund.finance.service;

import com.medfund.finance.client.ContributionsClient;
import com.medfund.finance.dto.CollectionRateTrendResponse;
import com.medfund.finance.dto.CollectionRateTrendResponse.MonthRow;
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
 * Unit tests for {@link CollectionRateTrendService}. Guards the Phase 8
 * flatten: every dimension's monthly buckets collapse into one strip per
 * (month, currency) with summed billed/received and a recomputed rate —
 * same arithmetic as the dimension report, never cross-currency.
 */
@ExtendWith(MockitoExtension.class)
class CollectionRateTrendServiceTest {

    @Mock private ContributionsClient contributionsClient;
    @InjectMocks private CollectionRateTrendService service;

    private final LocalDate periodStart = LocalDate.of(2026, 7, 1);
    private final LocalDate periodEnd   = LocalDate.of(2026, 8, 31);
    private final UUID schemeGold = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID memberAnne = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void compute_happyPath_sumsAcrossDimensionsPerMonthAndCurrency() {
        // July: scheme bills 100 / receives 50; member bills 50 / receives 0
        //      → strip shows billed 150, received 50, rate 33.33.
        // August: scheme bills 100 / receives 100 → rate 100.
        stubAll(
                List.of(billing(schemeGold, "Gold", "USD", LocalDate.of(2026, 7, 1), "100.00")),
                List.of(),
                List.of(billing(memberAnne, "Anne", "USD", LocalDate.of(2026, 7, 1), "50.00"),
                        billing(memberAnne, "Anne", "USD", LocalDate.of(2026, 8, 1), "100.00")),
                List.of(receipt(schemeGold, "Gold", "USD", LocalDate.of(2026, 7, 1), "50.00"),
                        receipt(schemeGold, "Gold", "USD", LocalDate.of(2026, 8, 1), "100.00")),
                List.of(),
                List.of());

        List<String> warnings = new ArrayList<>();
        StepVerifier.create(service.compute(periodStart, periodEnd, warnings))
                .assertNext(trend -> {
                    assertThat(trend.months()).hasSize(2);
                    MonthRow july = trend.months().get(0);
                    assertThat(july.month()).isEqualTo(LocalDate.of(2026, 7, 1));
                    assertThat(july.billed()).isEqualByComparingTo("150.00");
                    assertThat(july.received()).isEqualByComparingTo("50.00");
                    assertThat(july.ratePct()).isEqualByComparingTo("33.33");
                    MonthRow august = trend.months().get(1);
                    assertThat(august.billed()).isEqualByComparingTo("100.00");
                    assertThat(august.received()).isEqualByComparingTo("100.00");
                    assertThat(august.ratePct()).isEqualByComparingTo("100.00");
                })
                .verifyComplete();
        assertThat(warnings).isEmpty();
    }

    @Test
    void compute_receiptsPeerDown_returnsPartialDataWithWarnings() {
        when(contributionsClient.aggregateBillingMonthly(any(), any(), eq("SCHEME")))
                .thenReturn(Mono.just(List.of(
                        billing(schemeGold, "Gold", "USD", LocalDate.of(2026, 7, 1), "100.00"))));
        when(contributionsClient.aggregateBillingMonthly(any(), any(), eq("GROUP")))
                .thenReturn(Mono.just(List.of()));
        when(contributionsClient.aggregateBillingMonthly(any(), any(), eq("MEMBER")))
                .thenReturn(Mono.just(List.of()));
        when(contributionsClient.aggregateReceiptsMonthly(any(), any(), any()))
                .thenReturn(Mono.error(new RuntimeException("connection refused")));

        List<String> warnings = new ArrayList<>();
        StepVerifier.create(service.compute(periodStart, periodEnd, warnings))
                .assertNext(trend -> {
                    assertThat(trend.months()).hasSize(1);
                    assertThat(trend.months().get(0).billed()).isEqualByComparingTo("100.00");
                    assertThat(trend.months().get(0).received()).isEqualByComparingTo("0");
                    assertThat(trend.months().get(0).ratePct()).isEqualByComparingTo("0.00");
                })
                .verifyComplete();
        assertThat(warnings).hasSize(3);
        assertThat(warnings).allMatch(w -> w.contains("receipts-aggregate-monthly"));
    }

    @Test
    void compute_neverMixesCurrencies() {
        // Same month, two currencies on different dimensions → two MonthRows.
        stubAll(
                List.of(billing(schemeGold, "Gold", "USD", LocalDate.of(2026, 7, 1), "100.00")),
                List.of(),
                List.of(billing(memberAnne, "Anne", "ZWL", LocalDate.of(2026, 7, 1), "36500.00")),
                List.of(receipt(schemeGold, "Gold", "USD", LocalDate.of(2026, 7, 1), "50.00")),
                List.of(),
                List.of(receipt(memberAnne, "Anne", "ZWL", LocalDate.of(2026, 7, 1), "20000.00")));

        StepVerifier.create(service.compute(periodStart, periodEnd, new ArrayList<>()))
                .assertNext(trend -> {
                    assertThat(trend.months()).hasSize(2);
                    MonthRow usd = trend.months().stream()
                            .filter(m -> "USD".equals(m.currencyCode())).findFirst().orElseThrow();
                    MonthRow zwl = trend.months().stream()
                            .filter(m -> "ZWL".equals(m.currencyCode())).findFirst().orElseThrow();
                    assertThat(usd.ratePct()).isEqualByComparingTo("50.00");
                    assertThat(zwl.ratePct()).isEqualByComparingTo("54.79"); // 20000/36500
                })
                .verifyComplete();
    }

    @Test
    void compute_zeroBilling_producesNullRateNotDivideByZero() {
        stubAll(List.of(), List.of(), List.of(),
                List.of(receipt(schemeGold, "Gold", "USD", LocalDate.of(2026, 7, 1), "50.00")),
                List.of(), List.of());

        StepVerifier.create(service.compute(periodStart, periodEnd, new ArrayList<>()))
                .assertNext(trend -> {
                    assertThat(trend.months()).hasSize(1);
                    assertThat(trend.months().get(0).billed()).isEqualByComparingTo("0");
                    assertThat(trend.months().get(0).received()).isEqualByComparingTo("50.00");
                    assertThat(trend.months().get(0).ratePct()).isNull();
                })
                .verifyComplete();
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

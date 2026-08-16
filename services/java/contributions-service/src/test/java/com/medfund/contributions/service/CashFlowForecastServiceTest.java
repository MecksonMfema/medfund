package com.medfund.contributions.service;

import com.medfund.contributions.client.FinanceClient;
import com.medfund.contributions.dto.CashFlowForecastResponse;
import com.medfund.contributions.dto.CashFlowForecastResponse.CurrencySeries;
import com.medfund.contributions.dto.CashFlowForecastResponse.WeekBucket;
import com.medfund.contributions.dto.InvoiceReceiptRow;
import com.medfund.contributions.dto.PlannedOutflowRow;
import com.medfund.contributions.repository.CashFlowForecastQueryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CashFlowForecastService}. Guards the Phase 8
 * window + bucketing contract (D8-6): the window is
 * {@code [asOf, asOf + rollingWeeks*7)} clamped to 1..52, inflow is
 * bucketed by invoice due-date week, outflow by run created-at UTC week,
 * both on the SAME ISO Monday calendar, per currency, net = inflow -
 * outflow. Finance-service downtime never fails the forecast — the
 * guarded() call appends a warning and the outflow side reads zero.
 */
@ExtendWith(MockitoExtension.class)
class CashFlowForecastServiceTest {

    @Mock private CashFlowForecastQueryRepository queryRepository;
    @Mock private FinanceClient financeClient;
    @InjectMocks private CashFlowForecastService service;

    private final LocalDate asOf = LocalDate.of(2026, 8, 14); // a Friday
    private static final UUID runId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Test
    void buildWindow_default13Weeks_splitsOnIsoMondays() {
        CashFlowForecastService.ForecastWindow window = service.buildWindow(asOf, 13);

        assertThat(window.start()).isEqualTo(asOf);
        assertThat(window.end()).isEqualTo(asOf.plusDays(13 * 7L));
        assertThat(window.weekStarts()).hasSize(13);
        // ISO Monday start of the week containing the asOf Friday.
        assertThat(window.weekStarts().get(0)).isEqualTo(LocalDate.of(2026, 8, 10));
        // Back-to-back Mondays.
        assertThat(window.weekStarts().get(1)).isEqualTo(LocalDate.of(2026, 8, 17));
        assertThat(window.weekStarts().get(12)).isEqualTo(window.weekStarts().get(0).plusWeeks(12));
    }

    @Test
    void buildWindow_rollingWeeksBelowOne_rejected() {
        assertThatThrownBy(() -> service.buildWindow(asOf, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rollingWeeks");
        assertThatThrownBy(() -> service.buildWindow(asOf, -3))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void buildWindow_over52_clampedTo52() {
        CashFlowForecastService.ForecastWindow window = service.buildWindow(asOf, 80);
        assertThat(window.weekStarts()).hasSize(52);
        assertThat(window.end()).isEqualTo(asOf.plusDays(52 * 7L));
    }

    @Test
    void weekStart_isIsoMonday() {
        assertThat(CashFlowForecastService.weekStart(LocalDate.of(2026, 8, 14))) // Fri
                .isEqualTo(LocalDate.of(2026, 8, 10));
        assertThat(CashFlowForecastService.weekStart(LocalDate.of(2026, 8, 10))) // Mon
                .isEqualTo(LocalDate.of(2026, 8, 10));
        assertThat(CashFlowForecastService.weekStart(LocalDate.of(2026, 8, 16))) // Sun
                .isEqualTo(LocalDate.of(2026, 8, 10));
    }

    @Test
    void compute_happyPath_bucketsBothSidesOnSameIsoCalendar() {
        // Inflow: invoice due 2026-08-11 (Mon week) → 100; due 2026-08-18 (next week) → 200.
        // Outflow: run created 2026-08-12 10:00Z → week of 08-10 → 40.
        // → week1: inflow 100, outflow 40, net 60; week2: inflow 200, outflow 0, net 200.
        when(queryRepository.expectedReceipts(any(), any())).thenReturn(Flux.fromIterable(
                List.of(invoice("USD", LocalDate.of(2026, 8, 11), "100.00"),
                        invoice("USD", LocalDate.of(2026, 8, 18), "200.00"))));
        when(financeClient.plannedOutflows(any(), any())).thenReturn(Mono.just(List.of(
                outflow("USD", "40.00", Instant.parse("2026-08-12T10:00:00Z")))));

        List<String> warnings = new ArrayList<>();
        StepVerifier.create(service.compute(asOf, 13, warnings))
                .assertNext(forecast -> {
                    assertThat(forecast.asOf()).isEqualTo(asOf);
                    assertThat(forecast.rollingWeeks()).isEqualTo(13);
                    assertThat(forecast.series()).hasSize(1);
                    CurrencySeries usd = forecast.series().get(0);
                    assertThat(usd.totalInflow()).isEqualByComparingTo("300.00");
                    assertThat(usd.totalOutflow()).isEqualByComparingTo("40.00");
                    assertThat(usd.totalNet()).isEqualByComparingTo("260.00");
                    assertThat(usd.buckets()).hasSize(13);
                    WeekBucket w1 = usd.buckets().get(0);
                    assertThat(w1.weekStart()).isEqualTo(LocalDate.of(2026, 8, 10));
                    assertThat(w1.inflow()).isEqualByComparingTo("100.00");
                    assertThat(w1.outflow()).isEqualByComparingTo("40.00");
                    assertThat(w1.net()).isEqualByComparingTo("60.00");
                    WeekBucket w2 = usd.buckets().get(1);
                    assertThat(w2.inflow()).isEqualByComparingTo("200.00");
                    assertThat(w2.outflow()).isEqualByComparingTo("0");
                    assertThat(w2.net()).isEqualByComparingTo("200.00");
                })
                .verifyComplete();
        assertThat(warnings).isEmpty();
    }

    @Test
    void compute_financeDown_returnsAllZeroOutflowWithWarning() {
        when(queryRepository.expectedReceipts(any(), any())).thenReturn(Flux.fromIterable(
                List.of(invoice("USD", LocalDate.of(2026, 8, 11), "100.00"))));
        when(financeClient.plannedOutflows(any(), any()))
                .thenReturn(Mono.error(new RuntimeException("connection refused")));

        List<String> warnings = new ArrayList<>();
        StepVerifier.create(service.compute(asOf, 13, warnings))
                .assertNext(forecast -> {
                    CurrencySeries usd = forecast.series().get(0);
                    assertThat(usd.totalInflow()).isEqualByComparingTo("100.00");
                    assertThat(usd.totalOutflow()).isEqualByComparingTo("0");
                    assertThat(usd.buckets()).allMatch(b -> b.outflow().compareTo(BigDecimal.ZERO) == 0);
                })
                .verifyComplete();
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0)).contains("payment-run-outflows");
    }

    @Test
    void compute_neverMixesCurrencies() {
        when(queryRepository.expectedReceipts(any(), any())).thenReturn(Flux.fromIterable(
                List.of(invoice("USD", LocalDate.of(2026, 8, 11), "100.00"),
                        invoice("ZWL", LocalDate.of(2026, 8, 11), "36500.00"))));
        when(financeClient.plannedOutflows(any(), any())).thenReturn(Mono.just(List.of(
                outflow("USD", "40.00", Instant.parse("2026-08-12T10:00:00Z")))));

        StepVerifier.create(service.compute(asOf, 13, new ArrayList<>()))
                .assertNext(forecast -> {
                    assertThat(forecast.series()).hasSize(2);
                    CurrencySeries usd = forecast.series().stream()
                            .filter(s -> "USD".equals(s.currencyCode())).findFirst().orElseThrow();
                    CurrencySeries zwl = forecast.series().stream()
                            .filter(s -> "ZWL".equals(s.currencyCode())).findFirst().orElseThrow();
                    assertThat(usd.totalInflow()).isEqualByComparingTo("100.00");
                    assertThat(usd.totalOutflow()).isEqualByComparingTo("40.00");
                    assertThat(zwl.totalInflow()).isEqualByComparingTo("36500.00");
                    assertThat(zwl.totalOutflow()).isEqualByComparingTo("0");
                })
                .verifyComplete();
    }

    @Test
    void compute_emptyLedger_rendersFullZeroStrip() {
        when(queryRepository.expectedReceipts(any(), any())).thenReturn(Flux.empty());
        when(financeClient.plannedOutflows(any(), any())).thenReturn(Mono.just(List.of()));

        StepVerifier.create(service.compute(asOf, 13, new ArrayList<>()))
                .assertNext(forecast -> assertThat(forecast.series()).isEmpty())
                .verifyComplete();
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static InvoiceReceiptRow invoice(String ccy, LocalDate due, String amount) {
        return new InvoiceReceiptRow(ccy, due, new BigDecimal(amount));
    }

    private static PlannedOutflowRow outflow(String ccy, String amount, Instant createdAt) {
        return new PlannedOutflowRow(runId, "RUN-1", ccy, new BigDecimal(amount), "draft", "pending", createdAt);
    }
}

package com.medfund.contributions.premium.service;

import com.medfund.contributions.premium.dto.UprMovementRow;
import com.medfund.contributions.premium.repository.PremiumReportQueryRepository;
import com.medfund.shared.report.PerCurrencyTotal;
import com.medfund.shared.report.ReportEnvelopeBuilder;
import com.medfund.shared.report.ReportKey;
import com.medfund.shared.report.ReportPeriod;
import com.medfund.shared.report.ReportResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link UprMovementReportService}. Covers the delegation
 * into {@link PremiumReportQueryRepository} + {@link ReportEnvelopeBuilder}
 * — the SQL aggregate itself is exercised against real Postgres in the
 * deferred {@code UprMovementReportIT}.
 */
@ExtendWith(MockitoExtension.class)
class UprMovementReportServiceTest {

    @Mock PremiumReportQueryRepository queryRepository;
    @Mock ReportEnvelopeBuilder envelopeBuilder;

    @InjectMocks UprMovementReportService service;

    private static final LocalDate PERIOD_START = LocalDate.of(2026, 1, 1);
    private static final LocalDate PERIOD_END   = LocalDate.of(2026, 3, 31);

    @Test
    void movement_emptyPeriod_wrapsEmptyList() {
        when(queryRepository.uprMovementRows(PERIOD_START, PERIOD_END, null))
                .thenReturn(Flux.empty());
        when(queryRepository.uprMovementPerCurrencyTotals(PERIOD_START, PERIOD_END, null))
                .thenReturn(Mono.just(Map.of()));
        stubEnvelope(List.of(), Map.of(), "USD");

        StepVerifier.create(service.movement(PERIOD_START, PERIOD_END, null, null))
                .assertNext(r -> {
                    assertThat(r.reportKey()).isEqualTo(ReportKey.UPR_MOVEMENT.name());
                    assertThat(r.data()).isEmpty();
                })
                .verifyComplete();
    }

    @Test
    void movement_singleCurrency_delegatesRowsAndTotalsMatchingFilter() {
        UprMovementRow row = new UprMovementRow("LIFE", "USD",
                new BigDecimal("100.00"), new BigDecimal("1200.00"),
                new BigDecimal("300.00"), BigDecimal.ZERO, new BigDecimal("1000.00"));
        when(queryRepository.uprMovementRows(PERIOD_START, PERIOD_END, "LIFE"))
                .thenReturn(Flux.just(row));
        when(queryRepository.uprMovementPerCurrencyTotals(PERIOD_START, PERIOD_END, "LIFE"))
                .thenReturn(Mono.just(Map.of("USD", new PerCurrencyTotal(new BigDecimal("1000.00"), 1L))));
        stubEnvelope(List.of(row),
                Map.of("USD", new PerCurrencyTotal(new BigDecimal("1000.00"), 1L)), "USD");

        service.movement(PERIOD_START, PERIOD_END, "LIFE", null).block();

        verify(queryRepository).uprMovementRows(PERIOD_START, PERIOD_END, "LIFE");
        verify(queryRepository).uprMovementPerCurrencyTotals(PERIOD_START, PERIOD_END, "LIFE");
    }

    @Test
    void movement_multiCurrency_perCurrencyMapFlowsThroughEnvelope() {
        UprMovementRow zar = new UprMovementRow("HEALTH", "ZAR",
                BigDecimal.ZERO, new BigDecimal("500"), new BigDecimal("500"),
                BigDecimal.ZERO, BigDecimal.ZERO);
        UprMovementRow usd = new UprMovementRow("HEALTH", "USD",
                BigDecimal.ZERO, new BigDecimal("200"), new BigDecimal("100"),
                BigDecimal.ZERO, new BigDecimal("100"));
        Map<String, PerCurrencyTotal> perCcy = Map.of(
                "ZAR", new PerCurrencyTotal(BigDecimal.ZERO, 1L),
                "USD", new PerCurrencyTotal(new BigDecimal("100"), 1L));
        when(queryRepository.uprMovementRows(any(), any(), any())).thenReturn(Flux.just(zar, usd));
        when(queryRepository.uprMovementPerCurrencyTotals(any(), any(), any()))
                .thenReturn(Mono.just(perCcy));
        stubEnvelope(List.of(zar, usd), perCcy, "USD");

        StepVerifier.create(service.movement(PERIOD_START, PERIOD_END, null, null))
                .assertNext(r -> assertThat(r.perCurrency()).containsKeys("ZAR", "USD"))
                .verifyComplete();
    }

    @Test
    void movement_currencyOverride_forwardedToEnvelopeBuilder() {
        when(queryRepository.uprMovementRows(any(), any(), any())).thenReturn(Flux.empty());
        when(queryRepository.uprMovementPerCurrencyTotals(any(), any(), any()))
                .thenReturn(Mono.just(Map.of()));
        stubEnvelope(List.of(), Map.of(), "EUR");

        StepVerifier.create(service.movement(PERIOD_START, PERIOD_END, null, "EUR"))
                .assertNext(r -> assertThat(r.reportingCurrency()).isEqualTo("EUR"))
                .verifyComplete();

        verify(envelopeBuilder).<List<UprMovementRow>>build(
                eq(ReportKey.UPR_MOVEMENT), any(ReportPeriod.class), eq("EUR"), any(), any(Mono.class));
    }

    @Test
    void movement_reportKeyAndPeriodMatch() {
        when(queryRepository.uprMovementRows(any(), any(), any())).thenReturn(Flux.empty());
        when(queryRepository.uprMovementPerCurrencyTotals(any(), any(), any()))
                .thenReturn(Mono.just(Map.of()));
        stubEnvelope(List.of(), Map.of(), "USD");

        service.movement(PERIOD_START, PERIOD_END, null, null).block();

        ArgumentCaptor<ReportKey>    keyCap    = ArgumentCaptor.forClass(ReportKey.class);
        ArgumentCaptor<ReportPeriod> periodCap = ArgumentCaptor.forClass(ReportPeriod.class);
        verify(envelopeBuilder).<List<UprMovementRow>>build(
                keyCap.capture(), periodCap.capture(), any(), any(), any(Mono.class));
        assertThat(keyCap.getValue()).isEqualTo(ReportKey.UPR_MOVEMENT);
        assertThat(periodCap.getValue().periodStart()).isEqualTo(PERIOD_START);
        assertThat(periodCap.getValue().periodEnd()).isEqualTo(PERIOD_END);
        assertThat(periodCap.getValue().grain()).isEqualTo(ReportPeriod.PeriodGrain.CUSTOM);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private void stubEnvelope(List<UprMovementRow> rows,
                              Map<String, PerCurrencyTotal> perCurrency,
                              String reportingCurrency) {
        when(envelopeBuilder.<List<UprMovementRow>>build(
                        any(ReportKey.class), any(ReportPeriod.class), any(),
                        any(), any(Mono.class)))
                .thenReturn(Mono.just(ReportResponse.of(
                        ReportKey.UPR_MOVEMENT,
                        new ReportPeriod(PERIOD_START, PERIOD_END, ReportPeriod.PeriodGrain.CUSTOM),
                        reportingCurrency, rows, perCurrency, Map.of(), List.of())));
    }
}

package com.medfund.finance.producer.service;

import com.medfund.finance.producer.dto.ClawbackRegisterRow;
import com.medfund.finance.producer.repository.CommissionReportQueryRepository;
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

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CommissionClawbackReportService}. Covers the pure
 * period translation + envelope-builder delegation, plus the optional
 * {@code source} filter. Full envelope composition against real Postgres
 * runs in {@code CommissionReportIT}.
 */
@ExtendWith(MockitoExtension.class)
class CommissionClawbackReportServiceTest {

    @Mock CommissionReportQueryRepository queryRepository;
    @Mock ReportEnvelopeBuilder envelopeBuilder;

    @InjectMocks CommissionClawbackReportService service;

    private static final UUID PRODUCER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final LocalDate PERIOD_START = LocalDate.of(2026, 7, 1);
    private static final LocalDate PERIOD_END   = LocalDate.of(2026, 9, 30);

    @Test
    void register_translatesPeriodToExclusiveEndAndDelegates() {
        when(queryRepository.clawbackRows(any(), any(), any(), any()))
                .thenReturn(Flux.empty());
        when(queryRepository.clawbackPerCurrencyTotals(any(), any(), any(), any()))
                .thenReturn(Mono.just(Map.of()));
        when(envelopeBuilder.<List<ClawbackRegisterRow>>build(
                        any(ReportKey.class), any(ReportPeriod.class), any(),
                        any(), any(Mono.class)))
                .thenReturn(Mono.just(ReportResponse.of(
                        ReportKey.COMMISSION_CLAWBACK,
                        new ReportPeriod(PERIOD_START, PERIOD_END, ReportPeriod.PeriodGrain.CUSTOM),
                        "USD", List.of(), Map.of(), Map.of(), List.of())));

        StepVerifier.create(service.register(PERIOD_START, PERIOD_END, null, null, null))
                .assertNext(r -> assertThat(r.reportKey())
                        .isEqualTo(ReportKey.COMMISSION_CLAWBACK.name()))
                .verifyComplete();

        ArgumentCaptor<OffsetDateTime> fromCap = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<OffsetDateTime> toCap = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(queryRepository).clawbackRows(fromCap.capture(), toCap.capture(), eq(null), eq(null));
        assertThat(fromCap.getValue())
                .isEqualTo(OffsetDateTime.of(2026, 7, 1, 0, 0, 0, 0, ZoneOffset.UTC));
        assertThat(toCap.getValue())
                .isEqualTo(OffsetDateTime.of(2026, 10, 1, 0, 0, 0, 0, ZoneOffset.UTC));
    }

    @Test
    void register_producerFilter_forwardedToRepository() {
        when(queryRepository.clawbackRows(any(), any(), eq(PRODUCER_ID), any()))
                .thenReturn(Flux.empty());
        when(queryRepository.clawbackPerCurrencyTotals(any(), any(), eq(PRODUCER_ID), any()))
                .thenReturn(Mono.just(Map.of()));
        when(envelopeBuilder.<List<ClawbackRegisterRow>>build(
                        any(), any(), any(), any(), any(Mono.class)))
                .thenReturn(Mono.just(ReportResponse.of(
                        ReportKey.COMMISSION_CLAWBACK, null, "USD",
                        List.of(), Map.of(), Map.of(), List.of())));

        service.register(PERIOD_START, PERIOD_END, PRODUCER_ID, null, null).block();

        verify(queryRepository).clawbackRows(any(), any(), eq(PRODUCER_ID), eq(null));
        verify(queryRepository).clawbackPerCurrencyTotals(any(), any(), eq(PRODUCER_ID), eq(null));
    }

    @Test
    void register_sourceFilter_forwardedToRepository() {
        when(queryRepository.clawbackRows(any(), any(), any(), eq("MEMBER_LAPSE")))
                .thenReturn(Flux.empty());
        when(queryRepository.clawbackPerCurrencyTotals(any(), any(), any(), eq("MEMBER_LAPSE")))
                .thenReturn(Mono.just(Map.of()));
        when(envelopeBuilder.<List<ClawbackRegisterRow>>build(
                        any(), any(), any(), any(), any(Mono.class)))
                .thenReturn(Mono.just(ReportResponse.of(
                        ReportKey.COMMISSION_CLAWBACK, null, "USD",
                        List.of(), Map.of(), Map.of(), List.of())));

        service.register(PERIOD_START, PERIOD_END, null, "MEMBER_LAPSE", null).block();

        verify(queryRepository).clawbackRows(any(), any(), eq(null), eq("MEMBER_LAPSE"));
    }

    @Test
    void register_currencyOverride_forwardedToEnvelopeBuilder() {
        when(queryRepository.clawbackRows(any(), any(), any(), any()))
                .thenReturn(Flux.empty());
        when(queryRepository.clawbackPerCurrencyTotals(any(), any(), any(), any()))
                .thenReturn(Mono.just(Map.of()));
        when(envelopeBuilder.<List<ClawbackRegisterRow>>build(
                        any(), any(), eq("EUR"), any(), any(Mono.class)))
                .thenReturn(Mono.just(ReportResponse.of(
                        ReportKey.COMMISSION_CLAWBACK, null, "EUR",
                        List.of(), Map.of(), Map.of(), List.of())));

        StepVerifier.create(service.register(PERIOD_START, PERIOD_END, null, null, "EUR"))
                .assertNext(r -> assertThat(r.reportingCurrency()).isEqualTo("EUR"))
                .verifyComplete();
    }

    @Test
    void register_periodGrainIsCustom() {
        when(queryRepository.clawbackRows(any(), any(), any(), any()))
                .thenReturn(Flux.empty());
        when(queryRepository.clawbackPerCurrencyTotals(any(), any(), any(), any()))
                .thenReturn(Mono.just(Map.of()));
        when(envelopeBuilder.<List<ClawbackRegisterRow>>build(
                        any(), any(), any(), any(), any(Mono.class)))
                .thenReturn(Mono.just(ReportResponse.of(
                        ReportKey.COMMISSION_CLAWBACK, null, "USD",
                        List.of(), Map.of(), Map.of(), List.of())));

        service.register(PERIOD_START, PERIOD_END, null, null, null).block();

        ArgumentCaptor<ReportPeriod> periodCap = ArgumentCaptor.forClass(ReportPeriod.class);
        verify(envelopeBuilder).<List<ClawbackRegisterRow>>build(
                any(), periodCap.capture(), any(), any(), any(Mono.class));
        assertThat(periodCap.getValue().grain()).isEqualTo(ReportPeriod.PeriodGrain.CUSTOM);
        assertThat(periodCap.getValue().periodStart()).isEqualTo(PERIOD_START);
        assertThat(periodCap.getValue().periodEnd()).isEqualTo(PERIOD_END);
    }
}

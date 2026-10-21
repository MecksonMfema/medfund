package com.medfund.finance.producer.service;

import com.medfund.finance.producer.dto.CommissionStatementRow;
import com.medfund.finance.producer.repository.CommissionReportQueryRepository;
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
 * Unit tests for {@link CommissionStatementService}. Covers the pure
 * translation logic (LocalDate → exclusive-end OffsetDateTime, ReportPeriod
 * construction) and the delegation into {@link ReportEnvelopeBuilder}.
 * End-to-end envelope composition against real Postgres is exercised in
 * {@code CommissionReportIT}.
 */
@ExtendWith(MockitoExtension.class)
class CommissionStatementServiceTest {

    @Mock CommissionReportQueryRepository queryRepository;
    @Mock ReportEnvelopeBuilder envelopeBuilder;

    @InjectMocks CommissionStatementService service;

    private static final UUID PRODUCER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final LocalDate PERIOD_START = LocalDate.of(2026, 7, 1);
    private static final LocalDate PERIOD_END   = LocalDate.of(2026, 9, 30);

    @Test
    void statement_translatesPeriodToExclusiveEndAndDelegatesToEnvelope() {
        when(queryRepository.statementRows(any(), any(), any()))
                .thenReturn(Flux.empty());
        when(queryRepository.statementPerCurrencyTotals(any(), any(), any()))
                .thenReturn(Mono.just(Map.of()));
        when(envelopeBuilder.<List<CommissionStatementRow>>build(
                        any(ReportKey.class), any(ReportPeriod.class), any(),
                        any(), any(Mono.class)))
                .thenReturn(Mono.just(ReportResponse.of(
                        ReportKey.COMMISSION_STATEMENT,
                        new ReportPeriod(PERIOD_START, PERIOD_END, ReportPeriod.PeriodGrain.CUSTOM),
                        "USD", List.of(), Map.of(), Map.of(), List.of())));

        StepVerifier.create(service.statement(PERIOD_START, PERIOD_END, null, null))
                .assertNext(r -> assertThat(r.reportKey())
                        .isEqualTo(ReportKey.COMMISSION_STATEMENT.name()))
                .verifyComplete();

        // periodEnd + 1 day at start of day UTC — exclusive upper bound.
        ArgumentCaptor<OffsetDateTime> fromCap = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<OffsetDateTime> toCap = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(queryRepository).statementRows(fromCap.capture(), toCap.capture(), eq(null));
        assertThat(fromCap.getValue())
                .isEqualTo(OffsetDateTime.of(2026, 7, 1, 0, 0, 0, 0, ZoneOffset.UTC));
        assertThat(toCap.getValue())
                .isEqualTo(OffsetDateTime.of(2026, 10, 1, 0, 0, 0, 0, ZoneOffset.UTC));
    }

    @Test
    void statement_producerFilter_forwardedToRepository() {
        when(queryRepository.statementRows(any(), any(), eq(PRODUCER_ID)))
                .thenReturn(Flux.empty());
        when(queryRepository.statementPerCurrencyTotals(any(), any(), eq(PRODUCER_ID)))
                .thenReturn(Mono.just(Map.of()));
        when(envelopeBuilder.<List<CommissionStatementRow>>build(
                        any(), any(), any(), any(), any(Mono.class)))
                .thenReturn(Mono.just(ReportResponse.of(
                        ReportKey.COMMISSION_STATEMENT, null, "USD",
                        List.of(), Map.of(), Map.of(), List.of())));

        service.statement(PERIOD_START, PERIOD_END, PRODUCER_ID, null).block();

        verify(queryRepository).statementRows(any(), any(), eq(PRODUCER_ID));
        verify(queryRepository).statementPerCurrencyTotals(any(), any(), eq(PRODUCER_ID));
    }

    @Test
    void statement_currencyOverride_forwardedToEnvelopeBuilder() {
        when(queryRepository.statementRows(any(), any(), any()))
                .thenReturn(Flux.empty());
        when(queryRepository.statementPerCurrencyTotals(any(), any(), any()))
                .thenReturn(Mono.just(Map.of()));
        when(envelopeBuilder.<List<CommissionStatementRow>>build(
                        any(), any(), eq("EUR"), any(), any(Mono.class)))
                .thenReturn(Mono.just(ReportResponse.of(
                        ReportKey.COMMISSION_STATEMENT, null, "EUR",
                        List.of(), Map.of(), Map.of(), List.of())));

        StepVerifier.create(service.statement(PERIOD_START, PERIOD_END, null, "EUR"))
                .assertNext(r -> assertThat(r.reportingCurrency()).isEqualTo("EUR"))
                .verifyComplete();
    }

    @Test
    void statement_reportKeyIsCommissionStatement() {
        when(queryRepository.statementRows(any(), any(), any()))
                .thenReturn(Flux.empty());
        when(queryRepository.statementPerCurrencyTotals(any(), any(), any()))
                .thenReturn(Mono.just(Map.of()));
        when(envelopeBuilder.<List<CommissionStatementRow>>build(
                        any(), any(), any(), any(), any(Mono.class)))
                .thenReturn(Mono.just(ReportResponse.of(
                        ReportKey.COMMISSION_STATEMENT, null, "USD",
                        List.of(), Map.of(), Map.of(), List.of())));

        service.statement(PERIOD_START, PERIOD_END, null, null).block();

        ArgumentCaptor<ReportKey> keyCap = ArgumentCaptor.forClass(ReportKey.class);
        verify(envelopeBuilder).<List<CommissionStatementRow>>build(
                keyCap.capture(), any(), any(), any(), any(Mono.class));
        assertThat(keyCap.getValue()).isEqualTo(ReportKey.COMMISSION_STATEMENT);
    }

    @Test
    void statement_periodGrainIsCustom() {
        when(queryRepository.statementRows(any(), any(), any()))
                .thenReturn(Flux.empty());
        when(queryRepository.statementPerCurrencyTotals(any(), any(), any()))
                .thenReturn(Mono.just(Map.of("USD", new PerCurrencyTotal(BigDecimal.ZERO, 0L))));
        when(envelopeBuilder.<List<CommissionStatementRow>>build(
                        any(), any(), any(), any(), any(Mono.class)))
                .thenReturn(Mono.just(ReportResponse.of(
                        ReportKey.COMMISSION_STATEMENT, null, "USD",
                        List.of(), Map.of(), Map.of(), List.of())));

        service.statement(PERIOD_START, PERIOD_END, null, null).block();

        ArgumentCaptor<ReportPeriod> periodCap = ArgumentCaptor.forClass(ReportPeriod.class);
        verify(envelopeBuilder).<List<CommissionStatementRow>>build(
                any(), periodCap.capture(), any(), any(), any(Mono.class));
        assertThat(periodCap.getValue().grain()).isEqualTo(ReportPeriod.PeriodGrain.CUSTOM);
        assertThat(periodCap.getValue().periodStart()).isEqualTo(PERIOD_START);
        assertThat(periodCap.getValue().periodEnd()).isEqualTo(PERIOD_END);
    }
}

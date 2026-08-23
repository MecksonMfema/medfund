package com.medfund.contributions.premium.service;

import com.medfund.contributions.premium.dto.PremiumRegisterRow;
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
 * Unit tests for {@link PremiumRegisterReportService}. Coverage focuses
 * on delegation into the query repository + envelope builder plus the
 * shape of the wrapped payload; the seven-way UNION SQL is exercised
 * end-to-end in the deferred {@code PremiumRegisterReportIT}.
 */
@ExtendWith(MockitoExtension.class)
class PremiumRegisterReportServiceTest {

    @Mock PremiumReportQueryRepository queryRepository;
    @Mock ReportEnvelopeBuilder envelopeBuilder;

    @InjectMocks PremiumRegisterReportService service;

    private static final LocalDate PERIOD_START = LocalDate.of(2026, 4, 1);
    private static final LocalDate PERIOD_END   = LocalDate.of(2026, 6, 30);

    @Test
    void register_periodAndLineFilter_forwardedToRepository() {
        when(queryRepository.premiumRegisterRows(PERIOD_START, PERIOD_END, "VEHICLE"))
                .thenReturn(Flux.empty());
        when(queryRepository.premiumRegisterPerCurrencyTotals(PERIOD_START, PERIOD_END, "VEHICLE"))
                .thenReturn(Mono.just(Map.of()));
        stubEnvelope(List.of(), Map.of(), "USD");

        service.register(PERIOD_START, PERIOD_END, "VEHICLE", null).block();

        verify(queryRepository).premiumRegisterRows(PERIOD_START, PERIOD_END, "VEHICLE");
        verify(queryRepository).premiumRegisterPerCurrencyTotals(PERIOD_START, PERIOD_END, "VEHICLE");
    }

    @Test
    void register_newBusinessFlagFlowsThroughRow() {
        PremiumRegisterRow row = rowWithNewBusiness(true);
        when(queryRepository.premiumRegisterRows(any(), any(), any())).thenReturn(Flux.just(row));
        when(queryRepository.premiumRegisterPerCurrencyTotals(any(), any(), any()))
                .thenReturn(Mono.just(Map.of("USD", new PerCurrencyTotal(new BigDecimal("100"), 1L))));
        stubEnvelope(List.of(row),
                Map.of("USD", new PerCurrencyTotal(new BigDecimal("100"), 1L)), "USD");

        StepVerifier.create(service.register(PERIOD_START, PERIOD_END, null, null))
                .assertNext(r -> {
                    assertThat(r.data()).hasSize(1);
                    assertThat(r.data().get(0).isNewBusiness()).isTrue();
                })
                .verifyComplete();
    }

    @Test
    void register_portfolioAndCohortLabels_carriedThroughRow() {
        PremiumRegisterRow row = new PremiumRegisterRow(
                UUID.randomUUID(), "LIFE_POLICY", "Alice Doe", "LIFE",
                "Gold scheme", "USD",
                new BigDecimal("100"), new BigDecimal("50"), new BigDecimal("50"),
                OffsetDateTime.of(2026, 4, 1, 0, 0, 0, 0, ZoneOffset.UTC),
                LocalDate.of(2026, 4, 1), LocalDate.of(2027, 3, 31),
                false, "General Life 2026", "2026-Q2",
                PERIOD_START, PERIOD_END);
        when(queryRepository.premiumRegisterRows(any(), any(), any())).thenReturn(Flux.just(row));
        when(queryRepository.premiumRegisterPerCurrencyTotals(any(), any(), any()))
                .thenReturn(Mono.just(Map.of("USD", new PerCurrencyTotal(new BigDecimal("100"), 1L))));
        stubEnvelope(List.of(row),
                Map.of("USD", new PerCurrencyTotal(new BigDecimal("100"), 1L)), "USD");

        StepVerifier.create(service.register(PERIOD_START, PERIOD_END, null, null))
                .assertNext(r -> {
                    PremiumRegisterRow out = r.data().get(0);
                    assertThat(out.portfolioName()).isEqualTo("General Life 2026");
                    assertThat(out.cohortName()).isEqualTo("2026-Q2");
                    assertThat(out.schemeName()).isEqualTo("Gold scheme");
                    assertThat(out.memberName()).isEqualTo("Alice Doe");
                })
                .verifyComplete();
    }

    @Test
    void register_nativePerCurrencyMap_flowsThroughEnvelope() {
        Map<String, PerCurrencyTotal> perCcy = Map.of(
                "USD", new PerCurrencyTotal(new BigDecimal("100"), 1L),
                "ZAR", new PerCurrencyTotal(new BigDecimal("500"), 1L));
        when(queryRepository.premiumRegisterRows(any(), any(), any())).thenReturn(Flux.empty());
        when(queryRepository.premiumRegisterPerCurrencyTotals(any(), any(), any()))
                .thenReturn(Mono.just(perCcy));
        stubEnvelope(List.of(), perCcy, "USD");

        StepVerifier.create(service.register(PERIOD_START, PERIOD_END, null, null))
                .assertNext(r -> assertThat(r.perCurrency()).containsKeys("USD", "ZAR"))
                .verifyComplete();

        ArgumentCaptor<ReportKey> keyCap = ArgumentCaptor.forClass(ReportKey.class);
        verify(envelopeBuilder).<List<PremiumRegisterRow>>build(
                keyCap.capture(), any(ReportPeriod.class), any(), any(), any(Mono.class));
        assertThat(keyCap.getValue()).isEqualTo(ReportKey.PREMIUM_REGISTER);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private PremiumRegisterRow rowWithNewBusiness(boolean newBusiness) {
        return new PremiumRegisterRow(
                UUID.randomUUID(), "LIFE_POLICY", "Bob Doe", "LIFE",
                "Silver scheme", "USD",
                new BigDecimal("100"), new BigDecimal("100"), BigDecimal.ZERO,
                OffsetDateTime.of(2026, 4, 1, 0, 0, 0, 0, ZoneOffset.UTC),
                LocalDate.of(2026, 4, 1), LocalDate.of(2027, 3, 31),
                newBusiness, "MISC", "MISC-2026-DEFAULT",
                PERIOD_START, PERIOD_END);
    }

    private void stubEnvelope(List<PremiumRegisterRow> rows,
                              Map<String, PerCurrencyTotal> perCurrency,
                              String reportingCurrency) {
        when(envelopeBuilder.<List<PremiumRegisterRow>>build(
                        any(ReportKey.class), any(ReportPeriod.class), any(),
                        any(), any(Mono.class)))
                .thenReturn(Mono.just(ReportResponse.of(
                        ReportKey.PREMIUM_REGISTER,
                        new ReportPeriod(PERIOD_START, PERIOD_END, ReportPeriod.PeriodGrain.CUSTOM),
                        reportingCurrency, rows, perCurrency, Map.of(), List.of())));
    }
}

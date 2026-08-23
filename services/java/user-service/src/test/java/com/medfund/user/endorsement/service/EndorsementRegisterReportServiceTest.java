package com.medfund.user.endorsement.service;

import com.medfund.shared.report.PerCurrencyTotal;
import com.medfund.shared.report.ReportEnvelopeBuilder;
import com.medfund.shared.report.ReportKey;
import com.medfund.shared.report.ReportPeriod;
import com.medfund.shared.report.ReportResponse;
import com.medfund.user.endorsement.dto.EndorsementRegisterRow;
import com.medfund.user.endorsement.repository.EndorsementReportQueryRepository;
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
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EndorsementRegisterReportService}. Guards filter
 * forwarding + envelope wrapping — the SQL + UNION-ALL member lookup +
 * per-currency magnitude aggregation land in the deferred
 * {@code EndorsementRegisterReportIT}.
 */
@ExtendWith(MockitoExtension.class)
class EndorsementRegisterReportServiceTest {

    @Mock EndorsementReportQueryRepository queryRepository;
    @Mock ReportEnvelopeBuilder envelopeBuilder;

    @InjectMocks EndorsementRegisterReportService service;

    private static final LocalDate PERIOD_START = LocalDate.of(2026, 4, 1);
    private static final LocalDate PERIOD_END   = LocalDate.of(2026, 4, 30);

    @Test
    void register_emptyPeriod_returnsEmptyEnvelope() {
        when(queryRepository.rows(any(), any(), any(), any())).thenReturn(Flux.empty());
        when(queryRepository.perCurrencyTotals(any(), any(), any(), any()))
                .thenReturn(Mono.just(Map.of()));
        stubEnvelope(List.of(), Map.of(), "USD");

        StepVerifier.create(service.register(PERIOD_START, PERIOD_END, null, null, null))
                .assertNext(r -> assertThat(r.data()).isEmpty())
                .verifyComplete();
    }

    @Test
    void register_lineAndStatusFilters_forwardedToRepository() {
        when(queryRepository.rows(PERIOD_START, PERIOD_END, "LIFE", "COMMITTED"))
                .thenReturn(Flux.empty());
        when(queryRepository.perCurrencyTotals(PERIOD_START, PERIOD_END, "LIFE", "COMMITTED"))
                .thenReturn(Mono.just(Map.of()));
        stubEnvelope(List.of(), Map.of(), "USD");

        service.register(PERIOD_START, PERIOD_END, "LIFE", "COMMITTED", null).block();

        verify(queryRepository).rows(PERIOD_START, PERIOD_END, "LIFE", "COMMITTED");
        verify(queryRepository).perCurrencyTotals(PERIOD_START, PERIOD_END, "LIFE", "COMMITTED");
    }

    @Test
    void register_rowFieldsFlowThroughPayload() {
        EndorsementRegisterRow row = new EndorsementRegisterRow(
                UUID.randomUUID(), "END-2026-000001", UUID.randomUUID(), "LIFE_POLICY",
                "Alice Doe", "LIFE", "PREMIUM_ADJUSTMENT",
                LocalDate.of(2026, 4, 1), new BigDecimal("120.00"), "USD", "COMMITTED",
                "drafter@example.com", Instant.parse("2026-04-01T09:00:00Z"),
                "approver@example.com", Instant.parse("2026-04-02T09:00:00Z"),
                "approver@example.com", Instant.parse("2026-04-02T09:05:00Z"), null);
        when(queryRepository.rows(any(), any(), any(), any())).thenReturn(Flux.just(row));
        when(queryRepository.perCurrencyTotals(any(), any(), any(), any()))
                .thenReturn(Mono.just(Map.of("USD", new PerCurrencyTotal(new BigDecimal("120"), 1L))));
        stubEnvelope(List.of(row),
                Map.of("USD", new PerCurrencyTotal(new BigDecimal("120"), 1L)), "USD");

        StepVerifier.create(service.register(PERIOD_START, PERIOD_END, null, null, null))
                .assertNext(r -> {
                    EndorsementRegisterRow out = r.data().get(0);
                    assertThat(out.reference()).isEqualTo("END-2026-000001");
                    assertThat(out.memberName()).isEqualTo("Alice Doe");
                    assertThat(out.premiumDelta()).isEqualByComparingTo("120.00");
                    assertThat(out.currencyCode()).isEqualTo("USD");
                    assertThat(out.status()).isEqualTo("COMMITTED");
                })
                .verifyComplete();
    }

    @Test
    void register_reportKeyIsEndorsementRegister() {
        when(queryRepository.rows(any(), any(), any(), any())).thenReturn(Flux.empty());
        when(queryRepository.perCurrencyTotals(any(), any(), any(), any()))
                .thenReturn(Mono.just(Map.of()));
        stubEnvelope(List.of(), Map.of(), "USD");

        service.register(PERIOD_START, PERIOD_END, null, null, null).block();

        ArgumentCaptor<ReportKey> keyCap = ArgumentCaptor.forClass(ReportKey.class);
        verify(envelopeBuilder).<List<EndorsementRegisterRow>>build(
                keyCap.capture(), any(ReportPeriod.class), any(), any(), any(Mono.class));
        assertThat(keyCap.getValue()).isEqualTo(ReportKey.ENDORSEMENT_REGISTER);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private void stubEnvelope(List<EndorsementRegisterRow> rows,
                              Map<String, PerCurrencyTotal> perCurrency,
                              String reportingCurrency) {
        when(envelopeBuilder.<List<EndorsementRegisterRow>>build(
                        any(ReportKey.class), any(ReportPeriod.class), any(),
                        any(), any(Mono.class)))
                .thenReturn(Mono.just(ReportResponse.of(
                        ReportKey.ENDORSEMENT_REGISTER,
                        new ReportPeriod(PERIOD_START, PERIOD_END, ReportPeriod.PeriodGrain.CUSTOM),
                        reportingCurrency, rows, perCurrency, Map.of(), List.of())));
    }
}

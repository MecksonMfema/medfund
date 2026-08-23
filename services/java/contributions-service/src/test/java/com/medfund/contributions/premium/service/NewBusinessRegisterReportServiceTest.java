package com.medfund.contributions.premium.service;

import com.medfund.contributions.premium.dto.NewBusinessRegisterRow;
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
 * Unit tests for {@link NewBusinessRegisterReportService}. Guards the
 * filter forwarding + envelope wrapping. The annual-vs-HEALTH UNION
 * SQL, {@code renewed_from_policy_id IS NULL} filter, and the
 * {@code member_first_contribution} view join are exercised in the
 * deferred {@code NewBusinessRegisterReportIT}.
 */
@ExtendWith(MockitoExtension.class)
class NewBusinessRegisterReportServiceTest {

    @Mock PremiumReportQueryRepository queryRepository;
    @Mock ReportEnvelopeBuilder envelopeBuilder;

    @InjectMocks NewBusinessRegisterReportService service;

    private static final LocalDate PERIOD_START = LocalDate.of(2026, 1, 1);
    private static final LocalDate PERIOD_END   = LocalDate.of(2026, 1, 31);

    @Test
    void register_emptyPeriod_returnsEmptyEnvelope() {
        when(queryRepository.newBusinessRows(any(), any(), any())).thenReturn(Flux.empty());
        when(queryRepository.newBusinessPerCurrencyTotals(any(), any(), any()))
                .thenReturn(Mono.just(Map.of()));
        stubEnvelope(List.of(), Map.of(), "USD");

        StepVerifier.create(service.register(PERIOD_START, PERIOD_END, null, null))
                .assertNext(r -> assertThat(r.data()).isEmpty())
                .verifyComplete();
    }

    @Test
    void register_lineFilter_forwardedToRepository() {
        when(queryRepository.newBusinessRows(PERIOD_START, PERIOD_END, "LIFE"))
                .thenReturn(Flux.empty());
        when(queryRepository.newBusinessPerCurrencyTotals(PERIOD_START, PERIOD_END, "LIFE"))
                .thenReturn(Mono.just(Map.of()));
        stubEnvelope(List.of(), Map.of(), "USD");

        service.register(PERIOD_START, PERIOD_END, "LIFE", null).block();

        verify(queryRepository).newBusinessRows(PERIOD_START, PERIOD_END, "LIFE");
        verify(queryRepository).newBusinessPerCurrencyTotals(PERIOD_START, PERIOD_END, "LIFE");
    }

    @Test
    void register_rowFieldsFlowThroughPayload() {
        NewBusinessRegisterRow row = new NewBusinessRegisterRow(
                UUID.randomUUID(), "LIFE_POLICY", "MEM-001", "Alice Doe",
                "LIFE", "Gold scheme",
                OffsetDateTime.of(2026, 1, 15, 0, 0, 0, 0, ZoneOffset.UTC),
                new BigDecimal("1200"), "USD",
                "General Life 2026", "2026-Q1",
                LocalDate.of(2026, 1, 15), LocalDate.of(2027, 1, 14));
        when(queryRepository.newBusinessRows(any(), any(), any())).thenReturn(Flux.just(row));
        when(queryRepository.newBusinessPerCurrencyTotals(any(), any(), any()))
                .thenReturn(Mono.just(Map.of("USD", new PerCurrencyTotal(new BigDecimal("1200"), 1L))));
        stubEnvelope(List.of(row),
                Map.of("USD", new PerCurrencyTotal(new BigDecimal("1200"), 1L)), "USD");

        StepVerifier.create(service.register(PERIOD_START, PERIOD_END, null, null))
                .assertNext(r -> {
                    NewBusinessRegisterRow out = r.data().get(0);
                    assertThat(out.memberNumber()).isEqualTo("MEM-001");
                    assertThat(out.memberName()).isEqualTo("Alice Doe");
                    assertThat(out.insuranceLine()).isEqualTo("LIFE");
                    assertThat(out.writtenPremium()).isEqualByComparingTo("1200");
                    assertThat(out.currencyCode()).isEqualTo("USD");
                })
                .verifyComplete();
    }

    @Test
    void register_reportKeyIsNewBusinessRegister() {
        when(queryRepository.newBusinessRows(any(), any(), any())).thenReturn(Flux.empty());
        when(queryRepository.newBusinessPerCurrencyTotals(any(), any(), any()))
                .thenReturn(Mono.just(Map.of()));
        stubEnvelope(List.of(), Map.of(), "USD");

        service.register(PERIOD_START, PERIOD_END, null, null).block();

        ArgumentCaptor<ReportKey> keyCap = ArgumentCaptor.forClass(ReportKey.class);
        verify(envelopeBuilder).<List<NewBusinessRegisterRow>>build(
                keyCap.capture(), any(ReportPeriod.class), any(), any(), any(Mono.class));
        assertThat(keyCap.getValue()).isEqualTo(ReportKey.NEW_BUSINESS_REGISTER);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private void stubEnvelope(List<NewBusinessRegisterRow> rows,
                              Map<String, PerCurrencyTotal> perCurrency,
                              String reportingCurrency) {
        when(envelopeBuilder.<List<NewBusinessRegisterRow>>build(
                        any(ReportKey.class), any(ReportPeriod.class), any(),
                        any(), any(Mono.class)))
                .thenReturn(Mono.just(ReportResponse.of(
                        ReportKey.NEW_BUSINESS_REGISTER,
                        new ReportPeriod(PERIOD_START, PERIOD_END, ReportPeriod.PeriodGrain.CUSTOM),
                        reportingCurrency, rows, perCurrency, Map.of(), List.of())));
    }
}

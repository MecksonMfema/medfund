package com.medfund.user.reports.lifecycle.service;

import com.medfund.shared.report.ReportEnvelopeBuilder;
import com.medfund.shared.report.ReportKey;
import com.medfund.shared.report.ReportPeriod;
import com.medfund.shared.report.ReportResponse;
import com.medfund.user.reports.lifecycle.dto.PolicyMovementResult;
import com.medfund.user.reports.lifecycle.dto.PolicyMovementRow;
import com.medfund.user.reports.lifecycle.repository.PolicyLifecycleReportQueryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Phase 13 §C Phase 8 — PolicyMovementReportService wire-shape guard.
 * Repository interactions are mocked; the assertions confirm the service
 * threads the right arguments through to the envelope builder.
 */
@ExtendWith(MockitoExtension.class)
class PolicyMovementReportServiceTest {

    @Mock PolicyLifecycleReportQueryRepository queryRepository;
    @Mock ReportEnvelopeBuilder envelopeBuilder;

    @Test
    void generate_delegatesToEnvelopeBuilder_withMovementResult() {
        LocalDate ps = LocalDate.of(2026, 1, 1);
        LocalDate pe = LocalDate.of(2026, 3, 31);

        PolicyMovementRow row = new PolicyMovementRow(
                "LIFE_POLICY", "LIFE", "USD",
                10L, 3L, 1L, 2L, 1L, 11L,
                new BigDecimal("500.00"), new BigDecimal("100.00"));

        when(queryRepository.movementRows(ps, pe)).thenReturn(Flux.just(row));
        when(queryRepository.movementPerCurrencyTotals(ps, pe))
                .thenReturn(Mono.just(Map.of()));
        when(envelopeBuilder.build(eq(ReportKey.POLICY_MOVEMENT), any(ReportPeriod.class),
                any(), any(Mono.class), any(Mono.class)))
                .thenReturn(Mono.just(ReportResponse.of(
                        ReportKey.POLICY_MOVEMENT, null, "USD",
                        new PolicyMovementResult(List.of(row)),
                        Map.of(), Map.of(), List.of())));

        PolicyMovementReportService svc = new PolicyMovementReportService(queryRepository, envelopeBuilder);
        StepVerifier.create(svc.generate(ps, pe, null))
                .assertNext(response -> {
                    org.assertj.core.api.Assertions.assertThat(response.data().rows()).hasSize(1);
                    org.assertj.core.api.Assertions.assertThat(response.data().rows().get(0).policySource())
                            .isEqualTo("LIFE_POLICY");
                })
                .verifyComplete();
    }
}

package com.medfund.finance.regulatory.service;

import com.medfund.shared.report.FxRateReader;
import com.medfund.shared.report.ReportGenerationException;
import com.medfund.shared.report.ReportKey;
import com.medfund.shared.report.regulatory.RegulatoryReportGenerationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RegulatoryFxPolicyTest {

    @Mock
    private FxRateReader fxRateReader;
    @InjectMocks
    private RegulatoryFxPolicy policy;

    private static final UUID TENANT = UUID.randomUUID();
    private static final LocalDate AS_OF = LocalDate.of(2026, 6, 30);

    @Test
    void convert_sameCurrency_shortCircuits() {
        StepVerifier.create(policy.convert(
                        ReportKey.IPEC_QUARTERLY_RETURN, TENANT, AS_OF,
                        new BigDecimal("100"), "ZWL", "ZWL"))
                .expectNext(new BigDecimal("100"))
                .verifyComplete();
        verify(fxRateReader, never()).convert(any(), any(), any(), any(), any());
    }

    @Test
    void convert_nullAmount_zerosOut() {
        StepVerifier.create(policy.convert(
                        ReportKey.CMS_ASR, TENANT, AS_OF, null, "USD", "ZAR"))
                .expectNext(BigDecimal.ZERO)
                .verifyComplete();
        verify(fxRateReader, never()).convert(any(), any(), any(), any(), any());
    }

    @Test
    void convert_missingRate_translatesToRegulatoryException() {
        when(fxRateReader.convert(any(BigDecimal.class), any(String.class), any(String.class),
                any(LocalDate.class), any(UUID.class)))
                .thenReturn(Mono.error(new ReportGenerationException(
                        "No exchange rate for USD->ZWL as of 2026-06-30")));

        StepVerifier.create(policy.convert(
                        ReportKey.IPEC_QUARTERLY_RETURN, TENANT, AS_OF,
                        new BigDecimal("500"), "USD", "ZWL"))
                .expectErrorSatisfies(err -> {
                    org.assertj.core.api.Assertions.assertThat(err)
                            .isInstanceOf(RegulatoryReportGenerationException.class)
                            .hasMessageContaining("IPEC_QUARTERLY_RETURN")
                            .hasMessageContaining("USD")
                            .hasMessageContaining("ZWL")
                            .hasMessageContaining("2026-06-30")
                            .hasMessageContaining(TENANT.toString());
                    org.assertj.core.api.Assertions.assertThat(err.getCause())
                            .isInstanceOf(ReportGenerationException.class);
                })
                .verify();
    }

    @Test
    void convert_successfulRate_multipliesAndReturns() {
        when(fxRateReader.convert(new BigDecimal("100"), "USD", "ZWL", AS_OF, TENANT))
                .thenReturn(Mono.just(new BigDecimal("32000")));

        StepVerifier.create(policy.convert(
                        ReportKey.IPEC_QUARTERLY_RETURN, TENANT, AS_OF,
                        new BigDecimal("100"), "USD", "ZWL"))
                .expectNext(new BigDecimal("32000"))
                .verifyComplete();
    }

    @Test
    void convert_nullCurrencies_shortCircuit() {
        StepVerifier.create(policy.convert(
                        ReportKey.IPEC_QUARTERLY_RETURN, TENANT, AS_OF,
                        new BigDecimal("100"), null, "ZWL"))
                .expectNext(new BigDecimal("100"))
                .verifyComplete();
        StepVerifier.create(policy.convert(
                        ReportKey.IPEC_QUARTERLY_RETURN, TENANT, AS_OF,
                        new BigDecimal("100"), "USD", null))
                .expectNext(new BigDecimal("100"))
                .verifyComplete();
        verify(fxRateReader, never()).convert(any(), any(), any(), any(), any());
    }
}

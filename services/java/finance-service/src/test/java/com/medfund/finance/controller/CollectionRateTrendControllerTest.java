package com.medfund.finance.controller;

import com.medfund.finance.config.SecurityConfig;
import com.medfund.finance.dto.CollectionRateTrendResponse;
import com.medfund.finance.service.CollectionRateTrendExcelService;
import com.medfund.finance.service.CollectionRateTrendService;
import com.medfund.shared.report.FxRateReader;
import com.medfund.shared.report.ReportingCurrencyResolver;
import com.medfund.shared.security.SecurityEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

/**
 * WebFlux slice for {@link CollectionRateTrendController}. Pins route
 * wiring, envelope shape, and SecurityEvent emission on export. The
 * month-flatten logic is exercised by {@code CollectionRateTrendServiceTest}.
 */
@WebFluxTest(CollectionRateTrendController.class)
@Import(SecurityConfig.class)
class CollectionRateTrendControllerTest {

    @Autowired private WebTestClient webTestClient;

    @MockBean private CollectionRateTrendService trendService;
    @MockBean private CollectionRateTrendExcelService trendExcelService;
    @MockBean private ReportingCurrencyResolver currencyResolver;
    @MockBean private FxRateReader fxRateReader;
    @MockBean private SecurityEventPublisher securityEventPublisher;

    @BeforeEach
    void stubReturns() {
        when(currencyResolver.resolve(any(), any())).thenReturn(Mono.just("USD"));
        when(fxRateReader.findRate(any(), any(), any(), any())).thenReturn(Mono.empty());
        when(securityEventPublisher.publishDataAccess(any(), any(), any(), any(), any()))
                .thenReturn(Mono.empty());
        when(trendService.compute(any(), any(), any()))
                .thenReturn(Mono.just(new CollectionRateTrendResponse(
                        LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 31), List.of())));
    }

    @Test
    void report_returnsEnvelopeWithCorrectKey() {
        webTestClient.mutateWith(mockJwt())
                .get().uri(uri -> uri.path("/api/v1/reports/collection-rate-trend")
                        .queryParam("periodStart", "2026-07-01")
                        .queryParam("periodEnd",   "2026-08-31")
                        .build())
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.reportKey").isEqualTo("COLLECTION_RATE_TREND")
                .jsonPath("$.reportingCurrency").isEqualTo("USD")
                .jsonPath("$.data.months").isArray();
    }

    @Test
    void report_missingPeriodParams_returnsBadRequest() {
        webTestClient.mutateWith(mockJwt())
                .get().uri("/api/v1/reports/collection-rate-trend")
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().is4xxClientError();
    }

    @Test
    void exportExcel_emitsSecurityEventAndReturnsAttachment() {
        byte[] payload = new byte[]{9, 9, 9};
        when(trendExcelService.workbook(any(), any(), any())).thenReturn(Mono.just(payload));

        webTestClient.mutateWith(mockJwt())
                .get().uri(uri -> uri.path("/api/v1/reports/collection-rate-trend/export/excel")
                        .queryParam("periodStart", "2026-07-01")
                        .queryParam("periodEnd",   "2026-08-31")
                        .build())
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .expectHeader().valueMatches("Content-Disposition",
                        "attachment; filename=\"collection-rate-trend-2026-07-01-to-2026-08-31\\.xlsx\"")
                .expectBody(byte[].class).isEqualTo(payload);

        ArgumentCaptor<String> keyCap = ArgumentCaptor.forClass(String.class);
        verify(securityEventPublisher).publishDataAccess(any(), any(), any(),
                keyCap.capture(), any());
        assertThat(keyCap.getValue()).isEqualTo("COLLECTION_RATE_TREND");
    }
}

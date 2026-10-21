package com.medfund.finance.controller;

import com.medfund.finance.config.SecurityConfig;
import com.medfund.finance.dto.CollectionRateReportResponse;
import com.medfund.finance.service.CollectionRateExcelService;
import com.medfund.finance.service.CollectionRateReportService;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

/**
 * WebFlux slice for {@link CollectionRateReportController}. Pins route
 * wiring, envelope shape, and SecurityEvent emission on export. The
 * per-currency composition logic is exercised by
 * {@code CollectionRateReportServiceTest}.
 */
@WebFluxTest(CollectionRateReportController.class)
@Import(SecurityConfig.class)
class CollectionRateReportControllerTest {

    @Autowired private WebTestClient webTestClient;

    @MockBean private CollectionRateReportService collectionRateReportService;
    @MockBean private CollectionRateExcelService collectionRateExcelService;
    @MockBean private ReportingCurrencyResolver currencyResolver;
    @MockBean private SecurityEventPublisher securityEventPublisher;

    @BeforeEach
    void stubReturns() {
        when(currencyResolver.resolve(any(), any())).thenReturn(Mono.just("USD"));
        when(securityEventPublisher.publishDataAccess(any(), any(), any(), any(), any()))
                .thenReturn(Mono.empty());
        when(collectionRateReportService.compute(any(), any(), any()))
                .thenReturn(Mono.just(new CollectionRateReportResponse(
                        LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 31),
                        List.of(), List.of(), List.of())));
    }

    @Test
    void report_returnsEnvelopeWithCorrectKey() {
        webTestClient.mutateWith(mockJwt())
                .get().uri(uri -> uri.path("/api/v1/reports/collection-rate")
                        .queryParam("periodStart", "2026-07-01")
                        .queryParam("periodEnd",   "2026-08-31")
                        .build())
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.reportKey").isEqualTo("COLLECTION_RATE")
                .jsonPath("$.reportingCurrency").isEqualTo("USD")
                .jsonPath("$.data.byScheme").isArray();
    }

    @Test
    void report_missingPeriodParams_returnsBadRequest() {
        webTestClient.mutateWith(mockJwt())
                .get().uri("/api/v1/reports/collection-rate")
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().is4xxClientError();
    }

    @Test
    void exportExcel_emitsSecurityEventAndReturnsAttachment() {
        byte[] payload = new byte[]{9, 9, 9};
        when(collectionRateExcelService.workbook(any(), any(), any())).thenReturn(Mono.just(payload));

        webTestClient.mutateWith(mockJwt())
                .get().uri(uri -> uri.path("/api/v1/reports/collection-rate/export/excel")
                        .queryParam("periodStart", "2026-07-01")
                        .queryParam("periodEnd",   "2026-08-31")
                        .build())
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .expectHeader().valueMatches("Content-Disposition",
                        "attachment; filename=\"collection-rate-2026-07-01-to-2026-08-31\\.xlsx\"")
                .expectBody(byte[].class).isEqualTo(payload);

        ArgumentCaptor<String> keyCap = ArgumentCaptor.forClass(String.class);
        verify(securityEventPublisher).publishDataAccess(any(), any(), any(),
                keyCap.capture(), any());
        assertThat(keyCap.getValue()).isEqualTo("COLLECTION_RATE");
    }
}

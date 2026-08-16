package com.medfund.finance.controller;

import com.medfund.finance.config.SecurityConfig;
import com.medfund.finance.dto.LossRatioReportResponse;
import com.medfund.finance.dto.MemberPaymentsReportResponse;
import com.medfund.finance.service.CrossServiceReportService;
import com.medfund.finance.service.LossRatioExcelService;
import com.medfund.finance.service.MemberPaymentsExcelService;
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
 * WebFlux slice for {@link CrossServiceReportController}. Pins route
 * wiring, envelope shape, and SecurityEvent emission on export. The
 * cross-service composition logic is exercised by
 * {@code CrossServiceReportServiceTest}.
 */
@WebFluxTest(CrossServiceReportController.class)
@Import(SecurityConfig.class)
class CrossServiceReportControllerTest {

    @Autowired private WebTestClient webTestClient;

    @MockBean private CrossServiceReportService crossServiceReportService;
    @MockBean private LossRatioExcelService lossRatioExcelService;
    @MockBean private MemberPaymentsExcelService memberPaymentsExcelService;
    @MockBean private ReportingCurrencyResolver currencyResolver;
    @MockBean private SecurityEventPublisher securityEventPublisher;

    @BeforeEach
    void stubReturns() {
        when(currencyResolver.resolve(any(), any())).thenReturn(Mono.just("USD"));
        when(securityEventPublisher.publishDataAccess(any(), any(), any(), any(), any()))
                .thenReturn(Mono.empty());
        when(crossServiceReportService.lossRatio(any(), any(), any()))
                .thenReturn(Mono.just(new LossRatioReportResponse(
                        LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 31), List.of())));
        when(crossServiceReportService.memberPayments(any(), any(), any()))
                .thenReturn(Mono.just(new MemberPaymentsReportResponse(
                        LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 31), List.of())));
        when(lossRatioExcelService.workbook(any(), any(), any())).thenReturn(Mono.just(new byte[]{9, 9, 9}));
        when(memberPaymentsExcelService.workbook(any(), any(), any())).thenReturn(Mono.just(new byte[]{9, 9, 9}));
    }

    @Test
    void lossRatio_returnsEnvelopeWithCorrectKey() {
        webTestClient.mutateWith(mockJwt())
                .get().uri(uri -> uri.path("/api/v1/reports/billing-vs-claims")
                        .queryParam("periodStart", "2026-07-01")
                        .queryParam("periodEnd",   "2026-08-31")
                        .build())
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.reportKey").isEqualTo("LOSS_RATIO")
                .jsonPath("$.reportingCurrency").isEqualTo("USD")
                .jsonPath("$.data.rows").isArray();
    }

    @Test
    void memberPayments_returnsEnvelopeWithCorrectKey() {
        webTestClient.mutateWith(mockJwt())
                .get().uri(uri -> uri.path("/api/v1/reports/member-payments")
                        .queryParam("periodStart", "2026-07-01")
                        .queryParam("periodEnd",   "2026-08-31")
                        .build())
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.reportKey").isEqualTo("MEMBER_PAYMENTS_UNIFIED")
                .jsonPath("$.reportingCurrency").isEqualTo("USD")
                .jsonPath("$.data.rows").isArray();
    }

    @Test
    void report_missingPeriodParams_returnsBadRequest() {
        webTestClient.mutateWith(mockJwt())
                .get().uri("/api/v1/reports/billing-vs-claims")
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().is4xxClientError();
    }

    @Test
    void lossRatioExcel_emitsSecurityEventAndReturnsAttachment() {
        byte[] payload = new byte[]{9, 9, 9};
        when(lossRatioExcelService.workbook(any(), any(), any())).thenReturn(Mono.just(payload));

        webTestClient.mutateWith(mockJwt())
                .get().uri(uri -> uri.path("/api/v1/reports/billing-vs-claims/export/excel")
                        .queryParam("periodStart", "2026-07-01")
                        .queryParam("periodEnd",   "2026-08-31")
                        .build())
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .expectHeader().valueMatches("Content-Disposition",
                        "attachment; filename=\"loss-ratio-2026-07-01-to-2026-08-31\\.xlsx\"")
                .expectBody(byte[].class).isEqualTo(payload);

        ArgumentCaptor<String> keyCap = ArgumentCaptor.forClass(String.class);
        verify(securityEventPublisher).publishDataAccess(any(), any(), any(),
                keyCap.capture(), any());
        assertThat(keyCap.getValue()).isEqualTo("LOSS_RATIO");
    }

    @Test
    void memberPaymentsExcel_emitsSecurityEventAndReturnsAttachment() {
        byte[] payload = new byte[]{9, 9, 9};
        when(memberPaymentsExcelService.workbook(any(), any(), any())).thenReturn(Mono.just(payload));

        webTestClient.mutateWith(mockJwt())
                .get().uri(uri -> uri.path("/api/v1/reports/member-payments/export/excel")
                        .queryParam("periodStart", "2026-07-01")
                        .queryParam("periodEnd",   "2026-08-31")
                        .build())
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .expectHeader().valueMatches("Content-Disposition",
                        "attachment; filename=\"member-payments-2026-07-01-to-2026-08-31\\.xlsx\"")
                .expectBody(byte[].class).isEqualTo(payload);

        ArgumentCaptor<String> keyCap = ArgumentCaptor.forClass(String.class);
        verify(securityEventPublisher).publishDataAccess(any(), any(), any(),
                keyCap.capture(), any());
        assertThat(keyCap.getValue()).isEqualTo("MEMBER_PAYMENTS_UNIFIED");
    }
}

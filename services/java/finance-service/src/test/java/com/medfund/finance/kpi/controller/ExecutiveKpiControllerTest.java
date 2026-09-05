package com.medfund.finance.kpi.controller;

import com.medfund.finance.config.SecurityConfig;
import com.medfund.finance.kpi.dto.KpiDashboardResponse;
import com.medfund.finance.kpi.dto.KpiReportData;
import com.medfund.finance.kpi.dto.KpiRequest;
import com.medfund.finance.kpi.dto.KpiTrendPoint;
import com.medfund.finance.kpi.dto.KpiValue;
import com.medfund.finance.kpi.service.KpiComposerService;
import com.medfund.finance.kpi.service.KpiWorkbookService;
import com.medfund.shared.report.ReportEnablementReader;
import com.medfund.shared.security.SecurityEventPublisher;
import com.medfund.shared.report.ReportKey;
import com.medfund.shared.report.ReportPeriod;
import com.medfund.shared.report.ReportResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

/**
 * WebFlux slice for {@link ExecutiveKpiController}. Pins route wiring,
 * query-param → {@link KpiRequest} mapping and envelope shape. The
 * {@code @RequiresPermission} + {@code @RequiresReport} aspects are not
 * loaded in the WebFlux slice, so this test focuses on the request /
 * response contract; gate enforcement is covered by
 * {@code ExecutiveKpiControllerIT}. Composer maths live in
 * {@code KpiComposerServiceTest}.
 */
@WebFluxTest(ExecutiveKpiController.class)
@Import(SecurityConfig.class)
class ExecutiveKpiControllerTest {

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired private WebTestClient webTestClient;

    @MockBean private KpiComposerService composer;
    @MockBean private ReportEnablementReader reportEnablementReader;
    @MockBean private KpiWorkbookService workbookService;
    @MockBean private SecurityEventPublisher securityEventPublisher;

    @BeforeEach
    void stubComposer() {
        when(composer.lossRatio(any())).thenReturn(Mono.just(envelope(ReportKey.LOSS_RATIO_KPI, "USD",
                new BigDecimal("0.650000"), new BigDecimal("65"), new BigDecimal("100"))));
        when(composer.expenseRatio(any())).thenReturn(Mono.just(envelope(ReportKey.EXPENSE_RATIO, "USD",
                new BigDecimal("0.120000"), new BigDecimal("12"), new BigDecimal("100"))));
        when(composer.combinedRatio(any())).thenReturn(Mono.just(envelopeWithBasis(ReportKey.COMBINED_RATIO, "USD",
                new BigDecimal("0.770000"), new BigDecimal("77"), new BigDecimal("100"),
                "MIXED_LOSS_EARNED_EXPENSE_WRITTEN")));
        when(composer.claimsFrequency(any())).thenReturn(Mono.just(envelope(ReportKey.CLAIMS_FREQUENCY, "USD",
                new BigDecimal("0.041667"), new BigDecimal("5"), new BigDecimal("120"))));
        when(composer.averageSeverity(any())).thenReturn(Mono.just(envelope(ReportKey.AVERAGE_SEVERITY, "USD",
                new BigDecimal("450.000000"), new BigDecimal("2250"), new BigDecimal("5"))));
        when(reportEnablementReader.isEnabled(any(UUID.class), any())).thenReturn(Mono.just(true));
        when(composer.trend(any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(Mono.just(List.of(new KpiTrendPoint(
                        LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 1),
                        new KpiReportData(new BigDecimal("0.5"), new BigDecimal("50"),
                                new BigDecimal("100"), null, Map.of()),
                        Map.of(), List.of()))));
        when(composer.dashboard(any())).thenReturn(Mono.just(new KpiDashboardResponse(Map.of(
                ReportKey.LOSS_RATIO_KPI.name(),   envelope(ReportKey.LOSS_RATIO_KPI, "USD",
                        new BigDecimal("0.650000"), new BigDecimal("65"), new BigDecimal("100")),
                ReportKey.EXPENSE_RATIO.name(),    envelope(ReportKey.EXPENSE_RATIO, "USD",
                        new BigDecimal("0.120000"), new BigDecimal("12"), new BigDecimal("100")),
                ReportKey.COMBINED_RATIO.name(),   envelopeWithBasis(ReportKey.COMBINED_RATIO, "USD",
                        new BigDecimal("0.770000"), new BigDecimal("77"), new BigDecimal("100"),
                        "MIXED_LOSS_EARNED_EXPENSE_WRITTEN"),
                ReportKey.CLAIMS_FREQUENCY.name(), envelope(ReportKey.CLAIMS_FREQUENCY, "USD",
                        new BigDecimal("0.041667"), new BigDecimal("5"), new BigDecimal("120")),
                ReportKey.AVERAGE_SEVERITY.name(), envelope(ReportKey.AVERAGE_SEVERITY, "USD",
                        new BigDecimal("450.000000"), new BigDecimal("2250"), new BigDecimal("5"))))));
    }

    @Test
    void lossRatio_returnsEnvelopeAndPropagatesQueryParams() {
        UUID schemeId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        webTestClient.mutateWith(mockJwt())
                .get().uri(uri -> uri.path("/api/v1/reports/kpi/loss-ratio")
                        .queryParam("periodStart", "2026-07-01")
                        .queryParam("periodEnd",   "2026-08-01")
                        .queryParam("reportingCurrency", "USD")
                        .queryParam("insuranceLine", "HEALTH")
                        .queryParam("schemeId", schemeId.toString())
                        .build())
                .header("X-Tenant-ID", TENANT_ID.toString())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.reportKey").isEqualTo("LOSS_RATIO_KPI")
                .jsonPath("$.reportingCurrency").isEqualTo("USD")
                .jsonPath("$.data.compositeRatio").isEqualTo(0.65)
                .jsonPath("$.data.perCurrency.USD.ratio").isEqualTo(0.65);

        ArgumentCaptor<KpiRequest> captor = ArgumentCaptor.forClass(KpiRequest.class);
        verify(composer).lossRatio(captor.capture());
        assertThat(captor.getValue().tenantId()).isEqualTo(TENANT_ID);
        assertThat(captor.getValue().insuranceLine()).isEqualTo("HEALTH");
        assertThat(captor.getValue().schemeId()).isEqualTo(schemeId);
        assertThat(captor.getValue().reportingCurrency()).isEqualTo("USD");
    }

    @Test
    void expenseRatio_returnsEnvelope() {
        hit("/api/v1/reports/kpi/expense-ratio")
                .expectStatus().isOk()
                .expectBody().jsonPath("$.reportKey").isEqualTo("EXPENSE_RATIO");
    }

    @Test
    void combinedRatio_returnsEnvelopeWithBasisNote() {
        hit("/api/v1/reports/kpi/combined-ratio")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.reportKey").isEqualTo("COMBINED_RATIO")
                .jsonPath("$.data.basisNote").isEqualTo("MIXED_LOSS_EARNED_EXPENSE_WRITTEN");
    }

    @Test
    void claimsFrequency_returnsEnvelope() {
        hit("/api/v1/reports/kpi/claims-frequency")
                .expectStatus().isOk()
                .expectBody().jsonPath("$.reportKey").isEqualTo("CLAIMS_FREQUENCY");
    }

    @Test
    void averageSeverity_returnsEnvelope() {
        hit("/api/v1/reports/kpi/average-severity")
                .expectStatus().isOk()
                .expectBody().jsonPath("$.reportKey").isEqualTo("AVERAGE_SEVERITY");
    }

    @Test
    void dashboard_returnsFiveTiles() {
        hit("/api/v1/reports/kpi/dashboard")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.tiles.LOSS_RATIO_KPI.reportKey").isEqualTo("LOSS_RATIO_KPI")
                .jsonPath("$.tiles.EXPENSE_RATIO.reportKey").isEqualTo("EXPENSE_RATIO")
                .jsonPath("$.tiles.COMBINED_RATIO.reportKey").isEqualTo("COMBINED_RATIO")
                .jsonPath("$.tiles.CLAIMS_FREQUENCY.reportKey").isEqualTo("CLAIMS_FREQUENCY")
                .jsonPath("$.tiles.AVERAGE_SEVERITY.reportKey").isEqualTo("AVERAGE_SEVERITY");
    }

    @Test
    void trend_defaultsTo12Buckets_returnsTrendArray() {
        webTestClient.mutateWith(mockJwt())
                .get().uri(uri -> uri.path("/api/v1/reports/kpi/LOSS_RATIO_KPI/trend").build())
                .header("X-Tenant-ID", TENANT_ID.toString())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$").isArray()
                .jsonPath("$[0].periodStart").isEqualTo("2026-07-01");
    }

    @Test
    void trend_windowMonths24_ok() {
        webTestClient.mutateWith(mockJwt())
                .get().uri(uri -> uri.path("/api/v1/reports/kpi/EXPENSE_RATIO/trend")
                        .queryParam("windowMonths", 24)
                        .build())
                .header("X-Tenant-ID", TENANT_ID.toString())
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void trend_invalidWindow_returns400() {
        webTestClient.mutateWith(mockJwt())
                .get().uri(uri -> uri.path("/api/v1/reports/kpi/LOSS_RATIO_KPI/trend")
                        .queryParam("windowMonths", 17)
                        .build())
                .header("X-Tenant-ID", TENANT_ID.toString())
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void trend_unknownKey_returns400() {
        webTestClient.mutateWith(mockJwt())
                .get().uri(uri -> uri.path("/api/v1/reports/kpi/NOT_A_REAL_KEY/trend").build())
                .header("X-Tenant-ID", TENANT_ID.toString())
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void trend_nonDashboardKey_returns400() {
        // BILLING_REPORT exists but is not in ReportFamily.DASHBOARD
        webTestClient.mutateWith(mockJwt())
                .get().uri(uri -> uri.path("/api/v1/reports/kpi/BILLING_REPORT/trend").build())
                .header("X-Tenant-ID", TENANT_ID.toString())
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void trend_disabledKey_returns403() {
        when(reportEnablementReader.isEnabled(any(UUID.class),
                org.mockito.ArgumentMatchers.eq(ReportKey.LOSS_RATIO_KPI)))
                .thenReturn(Mono.just(false));
        webTestClient.mutateWith(mockJwt())
                .get().uri(uri -> uri.path("/api/v1/reports/kpi/LOSS_RATIO_KPI/trend").build())
                .header("X-Tenant-ID", TENANT_ID.toString())
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void exportExcel_returnsXlsxBytesWithContentDisposition() {
        when(workbookService.workbook(org.mockito.ArgumentMatchers.eq(ReportKey.LOSS_RATIO_KPI),
                any(KpiRequest.class))).thenReturn(Mono.just(new byte[]{1, 2, 3, 4}));
        when(securityEventPublisher.publishDataAccess(any(), any(), any(),
                org.mockito.ArgumentMatchers.eq("LOSS_RATIO_KPI"), any()))
                .thenReturn(Mono.empty());

        webTestClient.mutateWith(mockJwt())
                .get().uri(uri -> uri.path("/api/v1/reports/kpi/LOSS_RATIO_KPI/export/excel")
                        .queryParam("periodStart", "2026-07-01")
                        .queryParam("periodEnd",   "2026-08-01")
                        .build())
                .header("X-Tenant-ID", TENANT_ID.toString())
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .expectHeader().valueMatches("Content-Disposition",
                        "attachment; filename=\"loss_ratio_kpi-2026-07-01-to-2026-08-01\\.xlsx\"")
                .expectBody().consumeWith(res -> assertThat(res.getResponseBody()).isEqualTo(new byte[]{1, 2, 3, 4}));

        verify(workbookService).workbook(org.mockito.ArgumentMatchers.eq(ReportKey.LOSS_RATIO_KPI), any(KpiRequest.class));
        verify(securityEventPublisher).publishDataAccess(any(), any(), any(),
                org.mockito.ArgumentMatchers.eq("LOSS_RATIO_KPI"), any());
    }

    @Test
    void exportExcel_unknownKey_returns400() {
        webTestClient.mutateWith(mockJwt())
                .get().uri(uri -> uri.path("/api/v1/reports/kpi/NOT_A_REAL_KEY/export/excel")
                        .queryParam("periodStart", "2026-07-01")
                        .queryParam("periodEnd",   "2026-08-01")
                        .build())
                .header("X-Tenant-ID", TENANT_ID.toString())
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void exportExcel_nonDashboardKey_returns400() {
        webTestClient.mutateWith(mockJwt())
                .get().uri(uri -> uri.path("/api/v1/reports/kpi/BILLING_REPORT/export/excel")
                        .queryParam("periodStart", "2026-07-01")
                        .queryParam("periodEnd",   "2026-08-01")
                        .build())
                .header("X-Tenant-ID", TENANT_ID.toString())
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void exportExcel_disabledKey_returns403_andSkipsWorkbookAndAudit() {
        when(reportEnablementReader.isEnabled(any(UUID.class),
                org.mockito.ArgumentMatchers.eq(ReportKey.LOSS_RATIO_KPI)))
                .thenReturn(Mono.just(false));

        webTestClient.mutateWith(mockJwt())
                .get().uri(uri -> uri.path("/api/v1/reports/kpi/LOSS_RATIO_KPI/export/excel")
                        .queryParam("periodStart", "2026-07-01")
                        .queryParam("periodEnd",   "2026-08-01")
                        .build())
                .header("X-Tenant-ID", TENANT_ID.toString())
                .exchange()
                .expectStatus().isForbidden();

        // 403 short-circuits before both the workbook build and the audit publish.
        org.mockito.Mockito.verify(workbookService, org.mockito.Mockito.never())
                .workbook(any(), any());
        org.mockito.Mockito.verify(securityEventPublisher, org.mockito.Mockito.never())
                .publishDataAccess(any(), any(), any(), any(), any());
    }

    @Test
    void lossRatio_missingPeriodParams_returnsBadRequest() {
        webTestClient.mutateWith(mockJwt())
                .get().uri("/api/v1/reports/kpi/loss-ratio")
                .header("X-Tenant-ID", TENANT_ID.toString())
                .exchange()
                .expectStatus().is4xxClientError();
    }

    private WebTestClient.ResponseSpec hit(String path) {
        return webTestClient.mutateWith(mockJwt())
                .get().uri(uri -> uri.path(path)
                        .queryParam("periodStart", "2026-07-01")
                        .queryParam("periodEnd",   "2026-08-01")
                        .build())
                .header("X-Tenant-ID", TENANT_ID.toString())
                .exchange();
    }

    private static ReportResponse<KpiReportData> envelope(ReportKey key, String currency,
                                                          BigDecimal ratio, BigDecimal num, BigDecimal den) {
        return envelopeWithBasis(key, currency, ratio, num, den, null);
    }

    private static ReportResponse<KpiReportData> envelopeWithBasis(ReportKey key, String currency,
                                                                   BigDecimal ratio, BigDecimal num,
                                                                   BigDecimal den, String basisNote) {
        Map<String, KpiValue> perCurrency = Map.of(currency, new KpiValue(ratio, num, den, currency));
        KpiReportData data = new KpiReportData(ratio, num, den, basisNote, perCurrency);
        return new ReportResponse<>(
                key.name(),
                new ReportPeriod(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 1),
                        ReportPeriod.PeriodGrain.MONTHLY),
                currency,
                data,
                Map.of(),
                Map.of(),
                List.of(),
                OffsetDateTime.now());
    }
}

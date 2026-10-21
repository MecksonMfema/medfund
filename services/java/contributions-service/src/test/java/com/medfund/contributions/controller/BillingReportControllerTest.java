package com.medfund.contributions.controller;

import com.medfund.contributions.config.SecurityConfig;
import com.medfund.contributions.dto.GroupBillingSummaryRow;
import com.medfund.contributions.dto.SchemeBillingSummaryRow;
import com.medfund.contributions.service.BillingReportExcelService;
import com.medfund.contributions.service.BillingReportService;
import com.medfund.shared.report.PerCurrencyTotal;
import com.medfund.shared.report.ReportEnvelopeBuilder;
import com.medfund.shared.report.ReportKey;
import com.medfund.shared.report.ReportPeriod;
import com.medfund.shared.report.ReportResponse;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

/**
 * WebFlux slice test for {@link BillingReportController}. Covers:
 * <ul>
 *   <li>Route wiring — the four report endpoints and two exports land
 *       on the right handlers with the plan's query-param shape.</li>
 *   <li>Envelope delegation — controller passes tenant currency and
 *       period through to {@link ReportEnvelopeBuilder} unchanged so
 *       shape drift shows here, not in production.</li>
 *   <li>XLSX response envelope — Content-Type and Content-Disposition
 *       filename header. A broken filename saves the download without
 *       an extension.</li>
 *   <li>SecurityEvent emission fires on every export before bytes
 *       leave the server (matches Phase 0 pattern; asserted with an
 *       ArgumentCaptor on {@link SecurityEventPublisher}).</li>
 * </ul>
 *
 * <p>Report-toggle gating is not exercised here — the
 * {@link com.medfund.shared.report.ReportGuardAspect} isn't loaded
 * in the WebFlux slice. Aspect behaviour is pinned by
 * {@code ReportGuardAspectTest} in the shared module; the family
 * ITs cover the end-to-end 403 round-trip.
 */
@WebFluxTest(BillingReportController.class)
@Import(SecurityConfig.class)
class BillingReportControllerTest {

    @Autowired private WebTestClient webTestClient;

    @MockBean private BillingReportService billingReportService;
    @MockBean private BillingReportExcelService billingReportExcelService;
    @MockBean private ReportEnvelopeBuilder envelopeBuilder;
    @MockBean private SecurityEventPublisher securityEventPublisher;

    @BeforeEach
    void stubMockBeanReturns() {
        // Every export endpoint chains .thenReturn(bytes) after publishing —
        // a null Mono would NPE the chain and hide the wiring failure.
        when(securityEventPublisher.publishDataAccess(any(), any(), any(), any(), any()))
                .thenReturn(Mono.empty());

        // Mockito 5's any(Mono.class) matcher rejects nulls, so the service's
        // default null returns would break the envelopeBuilder matcher chain
        // and produce an empty-body 200. Stub the service methods to return
        // real (empty) Monos so the envelope-builder mock actually fires.
        when(billingReportService.perSchemeSummary(any(), any())).thenReturn(Mono.just(List.of()));
        when(billingReportService.perSchemePerCurrencyTotals(any(), any())).thenReturn(Mono.just(Map.of()));
        when(billingReportService.perGroupSummary(any(), any())).thenReturn(Mono.just(List.of()));
        when(billingReportService.perGroupPerCurrencyTotals(any(), any())).thenReturn(Mono.just(Map.of()));
        when(billingReportService.schemeDetail(any(), any(), any()))
                .thenReturn(Mono.empty());
        when(billingReportService.groupDetail(any(), any(), any()))
                .thenReturn(Mono.empty());
    }

    // ── /api/v1/reports/billing/schemes ─────────────────────────────────

    @Test
    void schemesReport_returnsEnvelopeFromBuilder() {
        ReportResponse<List<SchemeBillingSummaryRow>> envelope = new ReportResponse<>(
                ReportKey.BILLING_REPORT.name(),
                new ReportPeriod(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31),
                        ReportPeriod.PeriodGrain.MONTHLY),
                "USD",
                List.of(schemeRow()),
                Map.of("USD", new PerCurrencyTotal(new BigDecimal("100.00"), 1L)),
                Map.of("USD", BigDecimal.ONE),
                List.of(),
                OffsetDateTime.now());
        when(envelopeBuilder.build(eq(ReportKey.BILLING_REPORT), any(ReportPeriod.class),
                any(), any(Mono.class), any(Mono.class)))
                .thenReturn(Mono.just(envelope));

        webTestClient.mutateWith(mockJwt())
                .get().uri(uri -> uri.path("/api/v1/reports/billing/schemes")
                        .queryParam("periodStart", "2026-07-01")
                        .queryParam("periodEnd",   "2026-07-31")
                        .queryParam("reportingCurrency", "USD")
                        .build())
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.reportKey").isEqualTo("BILLING_REPORT")
                .jsonPath("$.reportingCurrency").isEqualTo("USD")
                .jsonPath("$.data.length()").isEqualTo(1)
                .jsonPath("$.perCurrency.USD.rowCount").isEqualTo(1);
    }

    @Test
    void schemesReport_missingPeriodParams_returnsBadRequest() {
        // ReportPeriod.parseFromQueryParams throws when either date is missing.
        // Silent 500s here would hide a broken client — the controller must
        // surface a 400 so the operator sees "provide both dates".
        webTestClient.mutateWith(mockJwt())
                .get().uri("/api/v1/reports/billing/schemes")
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().is4xxClientError();
    }

    @Test
    void exportSchemesExcel_emitsSecurityEventAndReturnsAttachment() {
        byte[] payload = new byte[]{1, 2, 3};
        when(billingReportExcelService.schemeReportExcel(any(), any(), any()))
                .thenReturn(Mono.just(payload));

        webTestClient.mutateWith(mockJwt())
                .get().uri(uri -> uri.path("/api/v1/reports/billing/schemes/export/excel")
                        .queryParam("periodStart", "2026-07-01")
                        .queryParam("periodEnd",   "2026-07-31")
                        .queryParam("reportingCurrency", "USD")
                        .build())
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .expectHeader().valueMatches("Content-Disposition",
                        "attachment; filename=\"billing-schemes-2026-07-01-to-2026-07-31\\.xlsx\"")
                .expectBody(byte[].class).isEqualTo(payload);

        ArgumentCaptor<String> keyCap = ArgumentCaptor.forClass(String.class);
        verify(securityEventPublisher).publishDataAccess(any(), any(), any(),
                keyCap.capture(), any());
        assertThat(keyCap.getValue()).isEqualTo(ReportKey.BILLING_REPORT.name());
    }

    // ── /api/v1/reports/billing/groups ──────────────────────────────────

    @Test
    void groupsReport_delegatesToEnvelopeBuilderWithCorrectKey() {
        ReportResponse<List<GroupBillingSummaryRow>> envelope = new ReportResponse<>(
                ReportKey.GROUP_BILLING_REPORT.name(),
                new ReportPeriod(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31),
                        ReportPeriod.PeriodGrain.MONTHLY),
                "USD",
                List.of(),
                Map.of(),
                Map.of(),
                List.of(),
                OffsetDateTime.now());
        when(envelopeBuilder.build(eq(ReportKey.GROUP_BILLING_REPORT), any(ReportPeriod.class),
                any(), any(Mono.class), any(Mono.class)))
                .thenReturn(Mono.just(envelope));

        webTestClient.mutateWith(mockJwt())
                .get().uri(uri -> uri.path("/api/v1/reports/billing/groups")
                        .queryParam("periodStart", "2026-07-01")
                        .queryParam("periodEnd",   "2026-07-31")
                        .build())
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.reportKey").isEqualTo("GROUP_BILLING_REPORT");
    }

    @Test
    void exportGroupsExcel_emitsSecurityEventWithGroupReportKey() {
        byte[] payload = new byte[]{4, 5, 6};
        when(billingReportExcelService.groupReportExcel(any(), any(), any()))
                .thenReturn(Mono.just(payload));

        webTestClient.mutateWith(mockJwt())
                .get().uri(uri -> uri.path("/api/v1/reports/billing/groups/export/excel")
                        .queryParam("periodStart", "2026-07-01")
                        .queryParam("periodEnd",   "2026-07-31")
                        .build())
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueMatches("Content-Disposition",
                        "attachment; filename=\"billing-groups-2026-07-01-to-2026-07-31\\.xlsx\"")
                .expectBody(byte[].class).isEqualTo(payload);

        verify(securityEventPublisher).publishDataAccess(any(), any(), any(),
                eq(ReportKey.GROUP_BILLING_REPORT.name()), any());
    }

    // ── Detail endpoints ────────────────────────────────────────────────

    @Test
    void schemeDetail_usesBuildNoAggregateWithSchemeDetailKey() {
        UUID schemeId = UUID.randomUUID();
        when(envelopeBuilder.buildNoAggregate(eq(ReportKey.SCHEME_BILLING_DETAIL),
                any(ReportPeriod.class), any(), any(Mono.class)))
                .thenReturn(Mono.just(new ReportResponse<>(
                        ReportKey.SCHEME_BILLING_DETAIL.name(),
                        new ReportPeriod(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31),
                                ReportPeriod.PeriodGrain.MONTHLY),
                        "USD", null, Map.of(), Map.of(), List.of(), OffsetDateTime.now())));

        webTestClient.mutateWith(mockJwt())
                .get().uri(uri -> uri.path("/api/v1/reports/billing/schemes/" + schemeId)
                        .queryParam("periodStart", "2026-07-01")
                        .queryParam("periodEnd",   "2026-07-31")
                        .build())
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.reportKey").isEqualTo("SCHEME_BILLING_DETAIL");
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private static SchemeBillingSummaryRow schemeRow() {
        return new SchemeBillingSummaryRow(
                UUID.randomUUID(), "Gold Plan", "HEALTH", "USD",
                10L, 5L, 15L,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("100.00"), new BigDecimal("50.00"));
    }
}

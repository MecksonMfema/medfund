package com.medfund.claims.controller;

import com.medfund.claims.config.SecurityConfig;
import com.medfund.claims.dto.ClaimStatusMatrixResponse;
import com.medfund.claims.dto.ClaimsDetailResponse;
import com.medfund.claims.dto.ClaimsSummaryRow;
import com.medfund.claims.dto.DenialAnalysisResponse;
import com.medfund.claims.dto.FrequencySeverityRow;
import com.medfund.claims.dto.HighCostClaimantRow;
import com.medfund.claims.dto.PageResponse;
import com.medfund.claims.dto.PreAuthActivityResponse;
import com.medfund.claims.service.ClaimsExcelService;
import com.medfund.claims.service.ClaimsReportService;
import com.medfund.claims.service.HighCostClaimantService;
import com.medfund.claims.service.PreAuthActivityService;
import com.medfund.shared.report.FxRateReader;
import com.medfund.shared.report.ReportEnvelopeBuilder;
import com.medfund.shared.report.ReportKey;
import com.medfund.shared.report.ReportPeriod;
import com.medfund.shared.report.ReportResponse;
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
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

/**
 * WebFlux slice test for {@link ClaimsReportController} (Phase 4 §A).
 * Same shape as {@code ReceiptsReportControllerTest} — pins route wiring,
 * envelope delegation, XLSX response envelope, and SecurityEvent emission
 * for the claims family's summary surfaces, drill-downs, and exports.
 *
 * <p>Report-toggle gating isn't exercised here — the aspect isn't loaded
 * in the WebFlux slice (see {@code ReceiptsReportControllerTest}).
 */
@WebFluxTest(ClaimsReportController.class)
@Import(SecurityConfig.class)
class ClaimsReportControllerTest {

    @Autowired private WebTestClient webTestClient;

    @MockBean private ClaimsReportService claimsReportService;
    @MockBean private HighCostClaimantService highCostClaimantService;
    @MockBean private PreAuthActivityService preAuthActivityService;
    @MockBean private ClaimsExcelService claimsExcelService;
    @MockBean private ReportEnvelopeBuilder envelopeBuilder;
    @MockBean private ReportingCurrencyResolver currencyResolver;
    @MockBean private FxRateReader fxRateReader;
    @MockBean private SecurityEventPublisher securityEventPublisher;

    private static final String START = "2026-07-01";
    private static final String END = "2026-07-31";

    @BeforeEach
    void stubMockBeanReturns() {
        when(securityEventPublisher.publishDataAccess(any(), any(), any(), any(), any()))
                .thenReturn(Mono.empty());
        // Mockito 5 — stub every Mono-returning service used in a compose
        // chain so no mock silently returns null mid-zip.
        when(claimsReportService.perSchemeSummary(any(), any(), any())).thenReturn(Mono.just(List.of()));
        when(claimsReportService.perProviderSummary(any(), any(), any())).thenReturn(Mono.just(List.of()));
        when(claimsReportService.claimsPerCurrencyTotals(any(), any(), any())).thenReturn(Mono.just(Map.of()));
        when(claimsReportService.detail(any(), any(), any(), any(), any(), any(), any(),
                anyInt(), anyInt())).thenReturn(Mono.empty());
        when(claimsReportService.aggregate(any(), any(), any())).thenReturn(Mono.just(List.of()));
        when(claimsReportService.aggregateMonthly(any(), any(), any())).thenReturn(Mono.just(List.of()));
        when(claimsReportService.perGroupSummary(any(), any(), any())).thenReturn(Mono.just(List.of()));
        when(claimsReportService.perMemberSummary(any(), any(), any(), any(), any(), any(),
                anyInt(), anyInt())).thenReturn(Mono.just(PageResponse.of(List.of(), 0L, 0, 50)));
        when(claimsReportService.memberPerCurrencyTotals(any(), any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(Map.of()));
        when(claimsReportService.statusMatrix(any(), any(), any())).thenReturn(Mono.just(
                new ClaimStatusMatrixResponse(LocalDate.parse(START), LocalDate.parse(END), List.of(),
                        java.time.Instant.now())));
        when(claimsReportService.statusMatrixDrill(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(Mono.just(PageResponse.of(List.of(), 0L, 0, 50)));
        when(claimsReportService.denialAnalysis(any(), any(), any(), any(), any())).thenReturn(Mono.just(
                new DenialAnalysisResponse(List.of(), List.of(), List.of(), List.of())));
        when(claimsReportService.frequencySeverity(any(), any(), any())).thenReturn(Mono.just(
                new ClaimsReportService.FrequencySeverityResult(List.of(), "Exposure proxy warning")));
        when(highCostClaimantService.report(any(), any(), any(), any())).thenReturn(Mono.just(
                new HighCostClaimantService.HighCostResult(List.of(), null)));
        when(highCostClaimantService.memberDetail(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(Mono.empty());
        when(preAuthActivityService.activity(any(), any(), any(), any())).thenReturn(Mono.just(
                new PreAuthActivityResponse(List.of(), new PreAuthActivityResponse.R04R05SignalRow(0, 0, null))));
    }

    // ── /schemes ─────────────────────────────────────────────────────────

    @Test
    void schemesReport_delegatesToEnvelopeBuilderWithClaimsSummaryKey() {
        when(envelopeBuilder.build(eq(ReportKey.CLAIMS_SUMMARY), any(ReportPeriod.class),
                any(), any(Mono.class), any(Mono.class)))
                .thenReturn(Mono.just(envelope(ReportKey.CLAIMS_SUMMARY, List.of())));

        webTestClient.mutateWith(mockJwt())
                .get().uri(uri -> uri.path("/api/v1/reports/claims/schemes")
                        .queryParam("periodStart", START)
                        .queryParam("periodEnd", END)
                        .queryParam("reportingCurrency", "USD")
                        .queryParam("insuranceLine", "HEALTH")
                        .build())
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.reportKey").isEqualTo(ReportKey.CLAIMS_SUMMARY.name())
                .jsonPath("$.reportingCurrency").isEqualTo("USD")
                .jsonPath("$.data.length()").isEqualTo(0);

        verify(claimsReportService).perSchemeSummary(any(), any(), any());
    }

    @Test
    void schemesReport_missingPeriodParams_returnsBadRequest() {
        webTestClient.mutateWith(mockJwt())
                .get().uri("/api/v1/reports/claims/schemes")
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().is4xxClientError();
    }

    @Test
    void exportSchemesExcel_emitsSecurityEventAndReturnsAttachment() {
        byte[] payload = new byte[]{1, 2, 3};
        when(claimsExcelService.schemesReportExcel(any(), any(), any(), any())).thenReturn(Mono.just(payload));

        webTestClient.mutateWith(mockJwt())
                .get().uri(uri -> uri.path("/api/v1/reports/claims/schemes/export/excel")
                        .queryParam("periodStart", START)
                        .queryParam("periodEnd", END)
                        .build())
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .expectHeader().valueMatches("Content-Disposition",
                        "attachment; filename=\"claims-schemes-2026-07-01-to-2026-07-31\\.xlsx\"")
                .expectBody(byte[].class).isEqualTo(payload);

        ArgumentCaptor<String> keyCap = ArgumentCaptor.forClass(String.class);
        verify(securityEventPublisher).publishDataAccess(any(), any(), any(), keyCap.capture(), any());
        assertThat(keyCap.getValue()).isEqualTo(ReportKey.CLAIMS_SUMMARY.name());
    }

    // ── /providers ───────────────────────────────────────────────────────

    @Test
    void providersReport_delegatesToEnvelopeBuilder() {
        when(envelopeBuilder.build(eq(ReportKey.CLAIMS_SUMMARY), any(ReportPeriod.class),
                any(), any(Mono.class), any(Mono.class)))
                .thenReturn(Mono.just(envelope(ReportKey.CLAIMS_SUMMARY, List.of())));

        webTestClient.mutateWith(mockJwt())
                .get().uri(uri -> uri.path("/api/v1/reports/claims/providers")
                        .queryParam("periodStart", START)
                        .queryParam("periodEnd", END)
                        .build())
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.reportKey").isEqualTo(ReportKey.CLAIMS_SUMMARY.name());
    }

    @Test
    void exportProvidersExcel_emitsSecurityEvent() {
        byte[] payload = new byte[]{4, 5, 6};
        when(claimsExcelService.providersReportExcel(any(), any(), any(), any())).thenReturn(Mono.just(payload));

        webTestClient.mutateWith(mockJwt())
                .get().uri(uri -> uri.path("/api/v1/reports/claims/providers/export/excel")
                        .queryParam("periodStart", START)
                        .queryParam("periodEnd", END)
                        .build())
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk()
                .expectBody(byte[].class).isEqualTo(payload);

        verify(securityEventPublisher).publishDataAccess(any(), any(), any(),
                eq(ReportKey.CLAIMS_SUMMARY.name()), any());
    }

    // ── Detail drill-downs ───────────────────────────────────────────────

    @Test
    void schemeDetail_usesBuildNoAggregateWithClaimsSummaryKey() {
        UUID schemeId = UUID.randomUUID();
        when(envelopeBuilder.buildNoAggregate(eq(ReportKey.CLAIMS_SUMMARY),
                any(ReportPeriod.class), any(), any(Mono.class)))
                .thenReturn(Mono.just(envelope(ReportKey.CLAIMS_SUMMARY, null)));

        webTestClient.mutateWith(mockJwt())
                .get().uri(uri -> uri.path("/api/v1/reports/claims/schemes/" + schemeId)
                        .queryParam("periodStart", START)
                        .queryParam("periodEnd", END)
                        .queryParam("status", "PAID")
                        .build())
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.reportKey").isEqualTo(ReportKey.CLAIMS_SUMMARY.name());

        verify(claimsReportService).detail(eq("SCHEME"), eq(schemeId), any(), any(),
                any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void providerDetail_usesBuildNoAggregate() {
        UUID providerId = UUID.randomUUID();
        when(envelopeBuilder.buildNoAggregate(eq(ReportKey.CLAIMS_SUMMARY),
                any(ReportPeriod.class), any(), any(Mono.class)))
                .thenReturn(Mono.just(envelope(ReportKey.CLAIMS_SUMMARY, null)));

        webTestClient.mutateWith(mockJwt())
                .get().uri(uri -> uri.path("/api/v1/reports/claims/providers/" + providerId)
                        .queryParam("periodStart", START)
                        .queryParam("periodEnd", END)
                        .build())
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk();

        verify(claimsReportService).detail(eq("PROVIDER"), eq(providerId), any(), any(),
                any(), isNull(), any(), anyInt(), anyInt());
    }

    @Test
    void exportSchemeDetailExcel_emitsSecurityEvent() {
        byte[] payload = new byte[]{7, 8, 9};
        when(claimsExcelService.detailExcel(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(payload));

        webTestClient.mutateWith(mockJwt())
                .get().uri(uri -> uri.path("/api/v1/reports/claims/schemes/" + UUID.randomUUID()
                                + "/export/excel")
                        .queryParam("periodStart", START)
                        .queryParam("periodEnd", END)
                        .build())
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk()
                .expectBody(byte[].class).isEqualTo(payload);

        verify(securityEventPublisher).publishDataAccess(any(), any(), any(),
                eq(ReportKey.CLAIMS_SUMMARY.name()), any());
    }

    // ── /groups (CLAIMS_SUMMARY §B) ───────────────────────────────────────

    @Test
    void groupsReport_delegatesToEnvelopeBuilder() {
        when(envelopeBuilder.build(eq(ReportKey.CLAIMS_SUMMARY), any(ReportPeriod.class),
                any(), any(Mono.class), any(Mono.class)))
                .thenReturn(Mono.just(envelope(ReportKey.CLAIMS_SUMMARY, List.of())));

        webTestClient.mutateWith(mockJwt())
                .get().uri(uri -> uri.path("/api/v1/reports/claims/groups")
                        .queryParam("periodStart", START)
                        .queryParam("periodEnd", END)
                        .build())
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.reportKey").isEqualTo(ReportKey.CLAIMS_SUMMARY.name());

        verify(claimsReportService).perGroupSummary(any(), any(), any());
    }

    @Test
    void exportGroupsExcel_emitsSecurityEvent() {
        byte[] payload = new byte[]{20, 21, 22};
        when(claimsExcelService.groupsReportExcel(any(), any(), any(), any())).thenReturn(Mono.just(payload));

        webTestClient.mutateWith(mockJwt())
                .get().uri(uri -> uri.path("/api/v1/reports/claims/groups/export/excel")
                        .queryParam("periodStart", START)
                        .queryParam("periodEnd", END)
                        .build())
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk()
                .expectBody(byte[].class).isEqualTo(payload);

        verify(securityEventPublisher).publishDataAccess(any(), any(), any(),
                eq(ReportKey.CLAIMS_SUMMARY.name()), any());
    }

    @Test
    void groupDetail_usesBuildNoAggregate() {
        when(envelopeBuilder.buildNoAggregate(eq(ReportKey.CLAIMS_SUMMARY),
                any(ReportPeriod.class), any(), any(Mono.class)))
                .thenReturn(Mono.just(envelope(ReportKey.CLAIMS_SUMMARY, null)));

        webTestClient.mutateWith(mockJwt())
                .get().uri(uri -> uri.path("/api/v1/reports/claims/groups/" + UUID.randomUUID())
                        .queryParam("periodStart", START)
                        .queryParam("periodEnd", END)
                        .build())
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk();

        verify(claimsReportService).detail(eq("GROUP"), any(), any(), any(),
                any(), any(), any(), anyInt(), anyInt());
    }

    // ── /members (CLAIMS_SUMMARY §B) ──────────────────────────────────────

    @Test
    void membersReport_usesBuildWithMemberPerCurrencyTotals() {
        when(envelopeBuilder.build(eq(ReportKey.CLAIMS_SUMMARY), any(ReportPeriod.class),
                any(), any(Mono.class), any(Mono.class)))
                .thenReturn(Mono.just(envelope(ReportKey.CLAIMS_SUMMARY, null)));

        webTestClient.mutateWith(mockJwt())
                .get().uri(uri -> uri.path("/api/v1/reports/claims/members")
                        .queryParam("periodStart", START)
                        .queryParam("periodEnd", END)
                        .queryParam("search", "ali")
                        .queryParam("insuranceLine", "HEALTH")
                        .queryParam("page", "1")
                        .queryParam("size", "25")
                        .build())
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.reportKey").isEqualTo(ReportKey.CLAIMS_SUMMARY.name());

        verify(claimsReportService).memberPerCurrencyTotals(any(), any(), any(), any(), any(), any());
    }

    @Test
    void exportMembersExcel_emitsSecurityEvent() {
        byte[] payload = new byte[]{23, 24, 25};
        when(claimsExcelService.membersReportExcel(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(payload));

        webTestClient.mutateWith(mockJwt())
                .get().uri(uri -> uri.path("/api/v1/reports/claims/members/export/excel")
                        .queryParam("periodStart", START)
                        .queryParam("periodEnd", END)
                        .build())
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk()
                .expectBody(byte[].class).isEqualTo(payload);

        verify(securityEventPublisher).publishDataAccess(any(), any(), any(),
                eq(ReportKey.CLAIMS_SUMMARY.name()), any());
    }

    @Test
    void memberDetail_usesBuildNoAggregate() {
        when(envelopeBuilder.buildNoAggregate(eq(ReportKey.CLAIMS_SUMMARY),
                any(ReportPeriod.class), any(), any(Mono.class)))
                .thenReturn(Mono.just(envelope(ReportKey.CLAIMS_SUMMARY, null)));

        webTestClient.mutateWith(mockJwt())
                .get().uri(uri -> uri.path("/api/v1/reports/claims/members/" + UUID.randomUUID())
                        .queryParam("periodStart", START)
                        .queryParam("periodEnd", END)
                        .build())
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk();

        verify(claimsReportService).detail(eq("MEMBER"), any(), any(), any(),
                any(), any(), any(), anyInt(), anyInt());
    }

    // ── /status-matrix (CLAIM_STATUS_LIST G49) ────────────────────────────

    @Test
    void statusMatrix_usesBuildNoAggregateWithStatusListKey() {
        when(envelopeBuilder.buildNoAggregate(eq(ReportKey.CLAIM_STATUS_LIST),
                any(ReportPeriod.class), any(), any(Mono.class)))
                .thenReturn(Mono.just(envelope(ReportKey.CLAIM_STATUS_LIST, null)));

        webTestClient.mutateWith(mockJwt())
                .get().uri(uri -> uri.path("/api/v1/reports/claims/status-matrix")
                        .queryParam("submittedFrom", START)
                        .queryParam("submittedTo", END)
                        .build())
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.reportKey").isEqualTo(ReportKey.CLAIM_STATUS_LIST.name());

        verify(claimsReportService).statusMatrix(any(), any(), any());
    }

    @Test
    void statusMatrixDrill_usesBuildNoAggregate() {
        when(envelopeBuilder.buildNoAggregate(eq(ReportKey.CLAIM_STATUS_LIST),
                any(ReportPeriod.class), any(), any(Mono.class)))
                .thenReturn(Mono.just(envelope(ReportKey.CLAIM_STATUS_LIST, null)));

        webTestClient.mutateWith(mockJwt())
                .get().uri(uri -> uri.path("/api/v1/reports/claims/status-matrix/drill")
                        .queryParam("submittedFrom", START)
                        .queryParam("submittedTo", END)
                        .queryParam("status", "REJECTED")
                        .queryParam("ageBucket", ">30")
                        .build())
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk();

        verify(claimsReportService).statusMatrixDrill(any(), any(), eq("REJECTED"), eq(">30"),
                anyInt(), anyInt());
    }

    @Test
    void exportStatusMatrixExcel_emitsSecurityEvent() {
        byte[] payload = new byte[]{26, 27, 28};
        when(claimsExcelService.statusMatrixExcel(any(), any(), any(), any())).thenReturn(Mono.just(payload));

        webTestClient.mutateWith(mockJwt())
                .get().uri(uri -> uri.path("/api/v1/reports/claims/status-matrix/export/excel")
                        .queryParam("submittedFrom", START)
                        .queryParam("submittedTo", END)
                        .build())
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk()
                .expectBody(byte[].class).isEqualTo(payload);

        verify(securityEventPublisher).publishDataAccess(any(), any(), any(),
                eq(ReportKey.CLAIM_STATUS_LIST.name()), any());
    }

    // ── /denial-analysis (DENIAL_ANALYSIS G47) ────────────────────────────

    @Test
    void denialAnalysis_usesBuildNoAggregateWithDenialKey() {
        when(envelopeBuilder.buildNoAggregate(eq(ReportKey.DENIAL_ANALYSIS),
                any(ReportPeriod.class), any(), any(Mono.class)))
                .thenReturn(Mono.just(envelope(ReportKey.DENIAL_ANALYSIS, null)));

        webTestClient.mutateWith(mockJwt())
                .get().uri(uri -> uri.path("/api/v1/reports/claims/denial-analysis")
                        .queryParam("periodStart", START)
                        .queryParam("periodEnd", END)
                        .build())
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.reportKey").isEqualTo(ReportKey.DENIAL_ANALYSIS.name());

        verify(claimsReportService).denialAnalysis(any(), any(), isNull(), isNull(), isNull());
    }

    @Test
    void exportDenialAnalysisExcel_emitsSecurityEvent() {
        byte[] payload = new byte[]{29, 30, 31};
        when(claimsExcelService.denialAnalysisExcel(any(), any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(payload));

        webTestClient.mutateWith(mockJwt())
                .get().uri(uri -> uri.path("/api/v1/reports/claims/denial-analysis/export/excel")
                        .queryParam("periodStart", START)
                        .queryParam("periodEnd", END)
                        .build())
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk()
                .expectBody(byte[].class).isEqualTo(payload);

        verify(securityEventPublisher).publishDataAccess(any(), any(), any(),
                eq(ReportKey.DENIAL_ANALYSIS.name()), any());
    }

    // ── /frequency-severity (CLAIMS_FREQUENCY_SEVERITY G48) ───────────────

    @Test
    void frequencySeverity_composesHandBuiltEnvelopeWithExposureWarning() {
        when(currencyResolver.resolve(isNull(), isNull())).thenReturn(Mono.just("USD"));

        webTestClient.mutateWith(mockJwt())
                .get().uri(uri -> uri.path("/api/v1/reports/claims/frequency-severity")
                        .queryParam("serviceFrom", START)
                        .queryParam("serviceTo", END)
                        .build())
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.reportKey").isEqualTo(ReportKey.CLAIMS_FREQUENCY_SEVERITY.name())
                .jsonPath("$.data.length()").isEqualTo(0)
                .jsonPath("$.warnings[0]").isEqualTo("Exposure proxy warning");

        verify(claimsReportService).frequencySeverity(any(), any(), isNull());
    }

    @Test
    void exportFrequencySeverityExcel_emitsSecurityEvent() {
        byte[] payload = new byte[]{32, 33, 34};
        when(claimsExcelService.frequencySeverityExcel(any(), any(), any(), any())).thenReturn(Mono.just(payload));

        webTestClient.mutateWith(mockJwt())
                .get().uri(uri -> uri.path("/api/v1/reports/claims/frequency-severity/export/excel")
                        .queryParam("serviceFrom", START)
                        .queryParam("serviceTo", END)
                        .build())
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk()
                .expectBody(byte[].class).isEqualTo(payload);

        verify(securityEventPublisher).publishDataAccess(any(), any(), any(),
                eq(ReportKey.CLAIMS_FREQUENCY_SEVERITY.name()), any());
    }

    // ── HIGH_COST_CLAIMANT ───────────────────────────────────────────────

    @Test
    void highCostClaimants_composesHandBuiltEnvelopeWithConfigGapWarning() {
        when(currencyResolver.resolve(isNull(), eq("USD"))).thenReturn(Mono.just("USD"));
        when(highCostClaimantService.report(any(), any(), eq("USD"), isNull())).thenReturn(Mono.just(
                new HighCostClaimantService.HighCostResult(
                        List.of(), "High-cost threshold not configured for tenant")));
        when(claimsReportService.claimsPerCurrencyTotals(any(), any(), any())).thenReturn(Mono.just(Map.of()));

        webTestClient.mutateWith(mockJwt())
                .get().uri(uri -> uri.path("/api/v1/reports/claims/high-cost-claimants")
                        .queryParam("periodStart", START)
                        .queryParam("periodEnd", END)
                        .queryParam("reportingCurrency", "USD")
                        .build())
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.reportKey").isEqualTo(ReportKey.HIGH_COST_CLAIMANT.name())
                .jsonPath("$.reportingCurrency").isEqualTo("USD")
                .jsonPath("$.data.length()").isEqualTo(0)
                .jsonPath("$.warnings[0]").isEqualTo("High-cost threshold not configured for tenant");
    }

    @Test
    void exportHighCostClaimantsExcel_emitsSecurityEvent() {
        byte[] payload = new byte[]{10, 11, 12};
        when(claimsExcelService.highCostClaimantsExcel(any(), any(), any())).thenReturn(Mono.just(payload));

        webTestClient.mutateWith(mockJwt())
                .get().uri(uri -> uri.path("/api/v1/reports/claims/high-cost-claimants/export/excel")
                        .queryParam("periodStart", START)
                        .queryParam("periodEnd", END)
                        .build())
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk()
                .expectBody(byte[].class).isEqualTo(payload);

        verify(securityEventPublisher).publishDataAccess(any(), any(), any(),
                eq(ReportKey.HIGH_COST_CLAIMANT.name()), any());
    }

    // ── PRE_AUTH_ACTIVITY ────────────────────────────────────────────────

    @Test
    void preAuthActivity_usesBuildNoAggregateWithPreAuthActivityKey() {
        when(envelopeBuilder.buildNoAggregate(eq(ReportKey.PRE_AUTH_ACTIVITY),
                any(ReportPeriod.class), any(), any(Mono.class)))
                .thenReturn(Mono.just(envelope(ReportKey.PRE_AUTH_ACTIVITY, null)));

        webTestClient.mutateWith(mockJwt())
                .get().uri(uri -> uri.path("/api/v1/reports/claims/pre-auth-activity")
                        .queryParam("periodStart", START)
                        .queryParam("periodEnd", END)
                        .build())
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.reportKey").isEqualTo(ReportKey.PRE_AUTH_ACTIVITY.name());

        verify(preAuthActivityService).activity(any(), any(), isNull(), isNull());
    }

    @Test
    void exportPreAuthActivityExcel_emitsSecurityEvent() {
        byte[] payload = new byte[]{13, 14, 15};
        when(claimsExcelService.preAuthActivityExcel(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(payload));

        webTestClient.mutateWith(mockJwt())
                .get().uri(uri -> uri.path("/api/v1/reports/claims/pre-auth-activity/export/excel")
                        .queryParam("periodStart", START)
                        .queryParam("periodEnd", END)
                        .build())
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk()
                .expectBody(byte[].class).isEqualTo(payload);

        verify(securityEventPublisher).publishDataAccess(any(), any(), any(),
                eq(ReportKey.PRE_AUTH_ACTIVITY.name()), any());
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static ClaimsSummaryRow summaryRow(String name, String ccy, int amount) {
        return new ClaimsSummaryRow(
                UUID.randomUUID(), name, null, ccy, 1,
                java.math.BigDecimal.valueOf(amount),
                java.math.BigDecimal.valueOf(amount),
                java.math.BigDecimal.valueOf(amount));
    }

    private static <T> ReportResponse<T> envelope(ReportKey key, T data) {
        return new ReportResponse<>(
                key.name(),
                new ReportPeriod(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31),
                        ReportPeriod.PeriodGrain.MONTHLY),
                "USD", data, Map.of(), Map.of(), List.of(), OffsetDateTime.now());
    }
}

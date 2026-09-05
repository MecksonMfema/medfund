package com.medfund.claims.report.schedule;

import com.medfund.claims.reports.provider.service.ProviderNetworkUtilizationWorkbookService;
import com.medfund.claims.service.ClaimsExcelService;
import com.medfund.claims.siu.dto.FraudReportData;
import com.medfund.claims.siu.service.FraudReportService;
import com.medfund.claims.siu.service.FraudReportWorkbookService;
import com.medfund.claims.siu.service.FraudReportWorkbookService.WorkbookOptions;
import com.medfund.shared.report.ReportKey;
import com.medfund.shared.report.ReportPeriod;
import com.medfund.shared.report.ReportResponse;
import com.medfund.shared.report.ScheduledRenderRequest;
import com.medfund.shared.security.SecurityEventPublisher;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScheduledRenderControllerTest {

    @Mock private ClaimsExcelService claimsExcelService;
    @Mock private ProviderNetworkUtilizationWorkbookService providerNetworkService;
    @Mock private FraudReportService fraudReportService;
    @Mock private FraudReportWorkbookService fraudReportWorkbookService;
    @Mock private SecurityEventPublisher securityEventPublisher;

    @Captor private ArgumentCaptor<WorkbookOptions> workbookOptionsCaptor;

    private ScheduledRenderController controller;

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID SCHEDULE = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();

    @BeforeEach
    void init() {
        controller = new ScheduledRenderController(
                claimsExcelService, providerNetworkService,
                fraudReportService, fraudReportWorkbookService,
                securityEventPublisher);
        lenient().when(securityEventPublisher.publishDataAccess(anyString(), any(), anyString(),
                anyString(), any())).thenReturn(Mono.empty());
    }

    private ScheduledRenderRequest req() {
        return new ScheduledRenderRequest(TENANT,
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31),
                LocalDate.of(2026, 8, 31), "USD",
                "Monthly", SCHEDULE, ACTOR, "admin@acme");
    }

    private ScheduledRenderRequest fraudReq(Map<String, Object> params) {
        return new ScheduledRenderRequest(TENANT,
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31),
                LocalDate.of(2026, 8, 31), "USD",
                "Weekly", SCHEDULE, ACTOR, "siusup@acme", params);
    }

    @Test
    void claimsSummary_delegates_andEmitsSecurityEvent() {
        byte[] bytes = new byte[]{1, 2};
        when(claimsExcelService.schemesReportExcel(any(LocalDate.class), any(LocalDate.class),
                eq("USD"), any()))
                .thenReturn(Mono.just(bytes));

        StepVerifier.create(controller.renderClaimsSummary(req()))
                .assertNext(resp -> assertThat(resp.getBody()).isEqualTo(bytes))
                .verifyComplete();

        verify(securityEventPublisher).publishDataAccess(
                eq(TENANT.toString()), eq(ACTOR.toString()), eq("admin@acme"),
                eq(ReportKey.CLAIMS_SUMMARY.name()), any());
    }

    @Test
    void providerNetworkUtilization_delegates() {
        byte[] bytes = new byte[]{7};
        when(providerNetworkService.workbook(any(LocalDate.class), any(LocalDate.class),
                any(), any(), eq("USD")))
                .thenReturn(Mono.just(bytes));

        StepVerifier.create(controller.renderProviderNetworkUtilization(req()))
                .assertNext(resp -> assertThat(resp.getBody()).isEqualTo(bytes))
                .verifyComplete();

        verify(securityEventPublisher).publishDataAccess(
                eq(TENANT.toString()), eq(ACTOR.toString()), eq("admin@acme"),
                eq(ReportKey.PROVIDER_NETWORK_UTILIZATION.name()), any());
    }

    // ── Phase 19 §B Phase 12 — FRAUD_SIU_REPORT scheduled-render ────────

    @Test
    void fraudSiuReport_defaultParams_omitsSensitiveSheets() {
        stubFraudRender(new byte[]{9});

        StepVerifier.create(controller.renderFraudSiuReport(fraudReq(null)))
                .assertNext(resp -> assertThat(resp.getBody()).isEqualTo(new byte[]{9}))
                .verifyComplete();

        verify(fraudReportWorkbookService).render(any(ReportResponse.class),
                workbookOptionsCaptor.capture(), any());
        assertThat(workbookOptionsCaptor.getValue().includeSensitiveSheets()).isFalse();
        verify(securityEventPublisher).publishDataAccess(
                eq(TENANT.toString()), eq(ACTOR.toString()), eq("siusup@acme"),
                eq(ReportKey.FRAUD_SIU_REPORT.name()), any());
    }

    @Test
    void fraudSiuReport_paramsOptIn_includesSensitiveSheets() {
        stubFraudRender(new byte[]{10});

        StepVerifier.create(controller.renderFraudSiuReport(
                        fraudReq(Map.of("includeSensitiveSheets", true))))
                .assertNext(resp -> assertThat(resp.getBody()).isEqualTo(new byte[]{10}))
                .verifyComplete();

        verify(fraudReportWorkbookService).render(any(ReportResponse.class),
                workbookOptionsCaptor.capture(), any());
        assertThat(workbookOptionsCaptor.getValue().includeSensitiveSheets()).isTrue();
    }

    @Test
    void fraudSiuReport_paramsNonBoolean_defaultsToFalse() {
        stubFraudRender(new byte[]{11});

        StepVerifier.create(controller.renderFraudSiuReport(
                        fraudReq(Map.of("includeSensitiveSheets", "yes"))))
                .assertNext(resp -> assertThat(resp.getBody()).isEqualTo(new byte[]{11}))
                .verifyComplete();

        verify(fraudReportWorkbookService).render(any(ReportResponse.class),
                workbookOptionsCaptor.capture(), any());
        // Non-Boolean value must not slip through as truthy — FR12 default.
        assertThat(workbookOptionsCaptor.getValue().includeSensitiveSheets()).isFalse();
    }

    @Test
    void readIncludeSensitiveSheets_handlesNullMap_missingKey_nonBoolean_true() {
        assertThat(ScheduledRenderController.readIncludeSensitiveSheets(
                new ScheduledRenderRequest(TENANT, null, null, null, null, null, null, null, null, null)))
                .isFalse();
        assertThat(ScheduledRenderController.readIncludeSensitiveSheets(
                fraudReq(Map.of()))).isFalse();
        assertThat(ScheduledRenderController.readIncludeSensitiveSheets(
                fraudReq(Map.of("other", true)))).isFalse();
        assertThat(ScheduledRenderController.readIncludeSensitiveSheets(
                fraudReq(Map.of("includeSensitiveSheets", "true")))).isFalse();
        assertThat(ScheduledRenderController.readIncludeSensitiveSheets(
                fraudReq(Map.of("includeSensitiveSheets", true)))).isTrue();
    }

    private void stubFraudRender(byte[] bytes) {
        FraudReportData data = new FraudReportData(
                0L, 0L, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, 0L, Map.of());
        ReportResponse<FraudReportData> envelope = ReportResponse.of(
                ReportKey.FRAUD_SIU_REPORT,
                new ReportPeriod(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), null),
                "USD",
                data,
                Map.of(),
                Map.of(),
                java.util.List.of());
        when(fraudReportService.summary(anyString(), anyString(), any()))
                .thenReturn(Mono.just(envelope));
        when(fraudReportWorkbookService.render(any(ReportResponse.class),
                any(WorkbookOptions.class), any()))
                .thenReturn(Mono.just(bytes));
    }
}

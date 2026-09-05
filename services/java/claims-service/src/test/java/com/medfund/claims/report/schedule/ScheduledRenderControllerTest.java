package com.medfund.claims.report.schedule;

import com.medfund.claims.reports.provider.service.ProviderNetworkUtilizationWorkbookService;
import com.medfund.claims.service.ClaimsExcelService;
import com.medfund.shared.report.ReportKey;
import com.medfund.shared.report.ScheduledRenderRequest;
import com.medfund.shared.security.SecurityEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDate;
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
    @Mock private SecurityEventPublisher securityEventPublisher;

    private ScheduledRenderController controller;

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID SCHEDULE = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();

    @BeforeEach
    void init() {
        controller = new ScheduledRenderController(
                claimsExcelService, providerNetworkService, securityEventPublisher);
        lenient().when(securityEventPublisher.publishDataAccess(anyString(), any(), anyString(),
                anyString(), any())).thenReturn(Mono.empty());
    }

    private ScheduledRenderRequest req() {
        return new ScheduledRenderRequest(TENANT,
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31),
                LocalDate.of(2026, 8, 31), "USD",
                "Monthly", SCHEDULE, ACTOR, "admin@acme");
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
}

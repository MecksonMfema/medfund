package com.medfund.contributions.report.schedule;

import com.medfund.contributions.premium.service.UprMovementWorkbookService;
import com.medfund.contributions.service.AgedBalancesExcelService;
import com.medfund.contributions.service.CashFlowForecastExcelService;
import com.medfund.shared.report.ReportKey;
import com.medfund.shared.report.ScheduledRenderRequest;
import com.medfund.shared.security.SecurityEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScheduledRenderControllerTest {

    @Mock private AgedBalancesExcelService agedBalancesService;
    @Mock private UprMovementWorkbookService uprService;
    @Mock private CashFlowForecastExcelService cashFlowService;
    @Mock private SecurityEventPublisher securityEventPublisher;

    private ScheduledRenderController controller;

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID SCHEDULE = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();

    @BeforeEach
    void init() {
        controller = new ScheduledRenderController(
                agedBalancesService, uprService, cashFlowService, securityEventPublisher);
        lenient().when(securityEventPublisher.publishDataAccess(anyString(), any(), anyString(),
                anyString(), any())).thenReturn(Mono.empty());
    }

    private ScheduledRenderRequest req(LocalDate ps, LocalDate pe, LocalDate asOf) {
        return new ScheduledRenderRequest(TENANT, ps, pe, asOf, "USD",
                "Monthly", SCHEDULE, ACTOR, "admin@acme");
    }

    @Test
    void agedDebtors_delegatesToAgedBalancesService_andEmitsSecurityEvent() {
        byte[] bytes = new byte[]{1, 2, 3};
        when(agedBalancesService.generate(eq("USD"), anyInt(), any()))
                .thenReturn(Mono.just(bytes));

        StepVerifier.create(controller.renderAgedDebtors(req(null, null, LocalDate.of(2026, 8, 31))))
                .assertNext(resp -> {
                    assertThat(resp.getStatusCode().value()).isEqualTo(200);
                    assertThat(resp.getBody()).isEqualTo(bytes);
                })
                .verifyComplete();

        ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(securityEventPublisher).publishDataAccess(
                eq(TENANT.toString()), eq(ACTOR.toString()), eq("admin@acme"),
                eq(ReportKey.AGED_DEBTORS.name()), details.capture());
        assertThat(details.getValue()).containsEntry("source", "SCHEDULED");
        assertThat(details.getValue()).containsEntry("scheduleId", SCHEDULE.toString());
    }

    @Test
    void uprMovement_passesTenantIdExplicitly() {
        byte[] bytes = new byte[]{9};
        when(uprService.workbook(any(LocalDate.class), any(LocalDate.class), any(), eq("USD"), eq(TENANT)))
                .thenReturn(Mono.just(bytes));

        LocalDate ps = LocalDate.of(2026, 8, 1);
        LocalDate pe = LocalDate.of(2026, 8, 31);
        StepVerifier.create(controller.renderUprMovement(req(ps, pe, null)))
                .assertNext(resp -> assertThat(resp.getBody()).isEqualTo(bytes))
                .verifyComplete();

        verify(uprService).workbook(eq(ps), eq(pe), any(), eq("USD"), eq(TENANT));
        verify(securityEventPublisher).publishDataAccess(
                eq(TENANT.toString()), eq(ACTOR.toString()), eq("admin@acme"),
                eq(ReportKey.UPR_MOVEMENT.name()), any());
    }

    @Test
    void cashFlow_uses13WeekHorizon_andPassesAsOf() {
        byte[] bytes = new byte[]{7};
        when(cashFlowService.workbook(any(LocalDate.class), eq(13), any()))
                .thenReturn(Mono.just(bytes));

        LocalDate asOf = LocalDate.of(2026, 8, 31);
        StepVerifier.create(controller.renderCashFlow(req(null, null, asOf)))
                .assertNext(resp -> assertThat(resp.getBody()).isEqualTo(bytes))
                .verifyComplete();

        verify(cashFlowService).workbook(eq(asOf), eq(13), any());
    }

    @Test
    void securityEventDetails_includeCadenceAndPeriodAndSize() {
        byte[] bytes = new byte[]{1, 2, 3, 4, 5};
        when(agedBalancesService.generate(any(), anyInt(), any())).thenReturn(Mono.just(bytes));

        StepVerifier.create(controller.renderAgedDebtors(req(null, null, LocalDate.of(2026, 8, 31))))
                .expectNextCount(1)
                .verifyComplete();

        ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(securityEventPublisher).publishDataAccess(any(), any(), any(), any(), details.capture());
        assertThat(details.getValue()).containsEntry("cadence", "Monthly");
        assertThat(details.getValue()).containsEntry("sizeBytes", bytes.length);
    }
}

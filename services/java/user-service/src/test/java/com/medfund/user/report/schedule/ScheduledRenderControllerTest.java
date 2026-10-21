package com.medfund.user.report.schedule;

import com.medfund.shared.report.ReportKey;
import com.medfund.shared.report.ScheduledRenderRequest;
import com.medfund.shared.security.SecurityEventPublisher;
import com.medfund.user.reports.lifecycle.service.GroupCensusWorkbookService;
import com.medfund.user.reports.lifecycle.service.PersistencyCohortWorkbookService;
import com.medfund.user.reports.lifecycle.service.PolicyMovementWorkbookService;
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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScheduledRenderControllerTest {

    @Mock private PolicyMovementWorkbookService policyMovementService;
    @Mock private PersistencyCohortWorkbookService persistencyCohortService;
    @Mock private GroupCensusWorkbookService groupCensusService;
    @Mock private SecurityEventPublisher securityEventPublisher;

    private ScheduledRenderController controller;

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID SCHEDULE = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();

    @BeforeEach
    void init() {
        controller = new ScheduledRenderController(policyMovementService, persistencyCohortService,
                groupCensusService, securityEventPublisher);
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
    void policyMovement_delegates_andEmitsSecurityEvent() {
        byte[] bytes = new byte[]{1};
        when(policyMovementService.workbook(any(LocalDate.class), any(LocalDate.class), eq("USD")))
                .thenReturn(Mono.just(bytes));

        StepVerifier.create(controller.renderPolicyMovement(req()))
                .assertNext(resp -> assertThat(resp.getBody()).isEqualTo(bytes))
                .verifyComplete();

        verify(securityEventPublisher).publishDataAccess(
                eq(TENANT.toString()), eq(ACTOR.toString()), eq("admin@acme"),
                eq(ReportKey.POLICY_MOVEMENT.name()), any());
    }

    @Test
    void persistencyCohort_passesDefaultCohortAndLine() {
        byte[] bytes = new byte[]{2};
        when(persistencyCohortService.workbook(any(LocalDate.class), any(LocalDate.class),
                isNull(), isNull(), eq("USD")))
                .thenReturn(Mono.just(bytes));

        StepVerifier.create(controller.renderPersistencyCohort(req()))
                .assertNext(resp -> assertThat(resp.getBody()).isEqualTo(bytes))
                .verifyComplete();

        verify(persistencyCohortService).workbook(
                eq(LocalDate.of(2026, 8, 1)), eq(LocalDate.of(2026, 8, 31)),
                isNull(), isNull(), eq("USD"));
    }

    @Test
    void groupCensus_asOfPathAndAllGroups() {
        byte[] bytes = new byte[]{3};
        when(groupCensusService.workbook(any(LocalDate.class), isNull(), isNull(), eq("USD")))
                .thenReturn(Mono.just(bytes));

        StepVerifier.create(controller.renderGroupCensus(req()))
                .assertNext(resp -> assertThat(resp.getBody()).isEqualTo(bytes))
                .verifyComplete();

        verify(groupCensusService).workbook(
                eq(LocalDate.of(2026, 8, 31)), isNull(), isNull(), eq("USD"));
    }
}

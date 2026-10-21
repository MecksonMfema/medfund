package com.medfund.finance.producer.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.finance.producer.entity.ClawbackEvent;
import com.medfund.finance.producer.service.CommissionClawbackService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommissionClawbackConsumerTest {

    @Mock CommissionClawbackService commissionClawbackService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private CommissionClawbackConsumer consumer() {
        return new CommissionClawbackConsumer(null, commissionClawbackService, objectMapper);
    }

    @Test
    void processEvent_lapsedStatus_dispatchesLapseClawback() {
        UUID memberId = UUID.randomUUID();
        String tenantId = UUID.randomUUID().toString();
        String json = String.format("""
                {"event":"MEMBER_STATUS_CHANGED",
                 "memberId":"%s",
                 "status":"lapsed",
                 "reason":"arrears",
                 "terminationDate":"2026-08-22",
                 "tenantId":"%s"}
                """, memberId, tenantId);
        when(commissionClawbackService.processMemberLapse(eq(memberId), any(Instant.class),
                anyString(), anyString(), anyString()))
                .thenReturn(Flux.just(new ClawbackEvent()));

        StepVerifier.create(consumer().processEvent(json)).verifyComplete();

        verify(commissionClawbackService).processMemberLapse(eq(memberId), any(Instant.class),
                anyString(), anyString(), anyString());
    }

    @Test
    void processEvent_terminatedStatus_dispatchesLapseClawback() {
        UUID memberId = UUID.randomUUID();
        String tenantId = UUID.randomUUID().toString();
        String json = String.format("""
                {"event":"MEMBER_STATUS_CHANGED",
                 "memberId":"%s",
                 "status":"terminated",
                 "reason":"",
                 "terminationDate":"2026-08-22",
                 "tenantId":"%s"}
                """, memberId, tenantId);
        when(commissionClawbackService.processMemberLapse(eq(memberId), any(Instant.class),
                anyString(), anyString(), anyString()))
                .thenReturn(Flux.just(new ClawbackEvent()));

        StepVerifier.create(consumer().processEvent(json)).verifyComplete();

        verify(commissionClawbackService).processMemberLapse(eq(memberId), any(Instant.class),
                anyString(), anyString(), anyString());
    }

    @Test
    void processEvent_activeStatus_skipsClawback() {
        String json = """
                {"event":"MEMBER_STATUS_CHANGED",
                 "memberId":"11111111-1111-4111-8111-111111111111",
                 "status":"active",
                 "tenantId":"22222222-2222-4222-8222-222222222222"}
                """;

        StepVerifier.create(consumer().processEvent(json)).verifyComplete();

        verify(commissionClawbackService, never()).processMemberLapse(any(), any(),
                anyString(), anyString(), anyString());
    }

    @Test
    void processEvent_suspendedStatus_skipsClawback() {
        String json = """
                {"event":"MEMBER_STATUS_CHANGED",
                 "memberId":"11111111-1111-4111-8111-111111111111",
                 "status":"suspended",
                 "tenantId":"22222222-2222-4222-8222-222222222222"}
                """;

        StepVerifier.create(consumer().processEvent(json)).verifyComplete();

        verify(commissionClawbackService, never()).processMemberLapse(any(), any(),
                anyString(), anyString(), anyString());
    }

    @Test
    void processEvent_missingTenantId_skipsClawback() {
        String json = """
                {"event":"MEMBER_STATUS_CHANGED",
                 "memberId":"11111111-1111-4111-8111-111111111111",
                 "status":"lapsed"}
                """;

        StepVerifier.create(consumer().processEvent(json)).verifyComplete();

        verify(commissionClawbackService, never()).processMemberLapse(any(), any(),
                anyString(), anyString(), anyString());
    }

    @Test
    void processEvent_wrongEventType_skipsClawback() {
        String json = """
                {"event":"MEMBER_ENROLLED",
                 "memberId":"11111111-1111-4111-8111-111111111111",
                 "status":"active",
                 "tenantId":"22222222-2222-4222-8222-222222222222"}
                """;

        StepVerifier.create(consumer().processEvent(json)).verifyComplete();

        verify(commissionClawbackService, never()).processMemberLapse(any(), any(),
                anyString(), anyString(), anyString());
    }

    @Test
    void processEvent_malformedJson_errorsBubble() {
        StepVerifier.create(consumer().processEvent("{not-json"))
                .expectError()
                .verify();

        verify(commissionClawbackService, never()).processMemberLapse(any(), any(),
                anyString(), anyString(), anyString());
    }
}

package com.medfund.finance.producer.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.finance.producer.entity.ClawbackEvent;
import com.medfund.finance.producer.service.CommissionClawbackService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
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
class CommissionRevokeConsumerTest {

    @Mock CommissionClawbackService commissionClawbackService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private CommissionRevokeConsumer consumer() {
        return new CommissionRevokeConsumer(null, commissionClawbackService, objectMapper);
    }

    @Test
    void processEvent_wellFormed_dispatchesToClawbackService() {
        UUID contributionId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        String tenantId = UUID.randomUUID().toString();
        String json = String.format("""
                {"event":"CONTRIBUTION_REVOKED",
                 "contributionId":"%s",
                 "memberId":"%s",
                 "amount":"250.00",
                 "currencyCode":"USD",
                 "insuranceLine":"HEALTH",
                 "tenantId":"%s",
                 "actorId":"admin-1",
                 "actorEmail":"admin@medfund.co",
                 "revokedAt":"2026-08-22T09:15:00Z"}
                """, contributionId, memberId, tenantId);
        when(commissionClawbackService.processContributionRevoke(eq(contributionId), eq(memberId),
                any(Instant.class), anyString(), anyString(), anyString()))
                .thenReturn(Mono.just(new ClawbackEvent()));

        StepVerifier.create(consumer().processEvent(json)).verifyComplete();

        verify(commissionClawbackService).processContributionRevoke(eq(contributionId), eq(memberId),
                any(Instant.class), anyString(), anyString(), anyString());
    }

    @Test
    void processEvent_wrongEventType_skipsDispatch() {
        String json = """
                {"event":"CONTRIBUTION_PAID",
                 "contributionId":"11111111-1111-4111-8111-111111111111",
                 "tenantId":"22222222-2222-4222-8222-222222222222"}
                """;

        StepVerifier.create(consumer().processEvent(json)).verifyComplete();

        verify(commissionClawbackService, never()).processContributionRevoke(any(), any(), any(),
                anyString(), anyString(), anyString());
    }

    @Test
    void processEvent_missingTenantId_skipsDispatch() {
        String json = """
                {"event":"CONTRIBUTION_REVOKED",
                 "contributionId":"11111111-1111-4111-8111-111111111111",
                 "memberId":"22222222-2222-4222-8222-222222222222"}
                """;

        StepVerifier.create(consumer().processEvent(json)).verifyComplete();

        verify(commissionClawbackService, never()).processContributionRevoke(any(), any(), any(),
                anyString(), anyString(), anyString());
    }

    @Test
    void processEvent_malformedJson_errorsBubble() {
        StepVerifier.create(consumer().processEvent("{not-json"))
                .expectError()
                .verify();

        verify(commissionClawbackService, never()).processContributionRevoke(any(), any(), any(),
                anyString(), anyString(), anyString());
    }
}

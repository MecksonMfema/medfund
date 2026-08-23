package com.medfund.finance.producer.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.finance.producer.dto.ContributionPaidEvent;
import com.medfund.finance.producer.entity.CommissionTransaction;
import com.medfund.finance.producer.service.CommissionCalcService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProducerCommissionConsumerTest {

    @Mock CommissionCalcService commissionCalcService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ProducerCommissionConsumer consumer() {
        return new ProducerCommissionConsumer(null, commissionCalcService, objectMapper);
    }

    @Test
    void processEvent_wellFormed_dispatchesToCalcService() {
        UUID contributionId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        String tenantId = UUID.randomUUID().toString();
        String json = String.format("""
                {"event":"CONTRIBUTION_PAID",
                 "contributionId":"%s",
                 "memberId":"%s",
                 "amount":"250.00",
                 "currencyCode":"USD",
                 "insuranceLine":"HEALTH",
                 "paidAt":"2026-08-22T09:15:00Z",
                 "tenantId":"%s"}
                """, contributionId, memberId, tenantId);
        when(commissionCalcService.processPaidContribution(any(), anyString(), anyString()))
                .thenReturn(Mono.just(new CommissionTransaction()));

        StepVerifier.create(consumer().processEvent(json)).verifyComplete();

        ArgumentCaptor<ContributionPaidEvent> cap = ArgumentCaptor.forClass(ContributionPaidEvent.class);
        verify(commissionCalcService).processPaidContribution(cap.capture(), anyString(), anyString());
        assertThat(cap.getValue().contributionId()).isEqualTo(contributionId);
        assertThat(cap.getValue().memberId()).isEqualTo(memberId);
        assertThat(cap.getValue().amount()).isEqualByComparingTo("250.00");
        assertThat(cap.getValue().tenantId()).isEqualTo(tenantId);
    }

    @Test
    void processEvent_wrongEventType_skipsDispatch() {
        String json = """
                {"event":"CONTRIBUTION_ADJUSTED",
                 "contributionId":"11111111-1111-4111-8111-111111111111",
                 "memberId":"22222222-2222-4222-8222-222222222222",
                 "amount":"250.00",
                 "insuranceLine":"HEALTH",
                 "tenantId":"33333333-3333-4333-8333-333333333333"}
                """;

        StepVerifier.create(consumer().processEvent(json)).verifyComplete();

        verify(commissionCalcService, never()).processPaidContribution(any(), anyString(), anyString());
    }

    @Test
    void processEvent_missingTenantId_skipsDispatch() {
        String json = """
                {"event":"CONTRIBUTION_PAID",
                 "contributionId":"11111111-1111-4111-8111-111111111111",
                 "memberId":"22222222-2222-4222-8222-222222222222",
                 "amount":"250.00",
                 "insuranceLine":"HEALTH"}
                """;

        StepVerifier.create(consumer().processEvent(json)).verifyComplete();

        verify(commissionCalcService, never()).processPaidContribution(any(), anyString(), anyString());
    }

    @Test
    void processEvent_missingContributionId_skipsDispatch() {
        String json = """
                {"event":"CONTRIBUTION_PAID",
                 "memberId":"22222222-2222-4222-8222-222222222222",
                 "amount":"250.00",
                 "insuranceLine":"HEALTH",
                 "tenantId":"33333333-3333-4333-8333-333333333333"}
                """;

        StepVerifier.create(consumer().processEvent(json)).verifyComplete();

        verify(commissionCalcService, never()).processPaidContribution(any(), anyString(), anyString());
    }

    @Test
    void processEvent_malformedJson_errorsBubble() {
        StepVerifier.create(consumer().processEvent("{not-json"))
                .expectError()
                .verify();

        verify(commissionCalcService, never()).processPaidContribution(any(), anyString(), anyString());
    }
}

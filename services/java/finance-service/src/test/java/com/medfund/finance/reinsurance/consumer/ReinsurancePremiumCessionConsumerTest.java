package com.medfund.finance.reinsurance.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.finance.reinsurance.dto.ContributionPaidEvent;
import com.medfund.finance.reinsurance.entity.Cession;
import com.medfund.finance.reinsurance.service.PremiumCessionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test on {@link ReinsurancePremiumCessionConsumer#processEvent}.
 * Kafka Receiver wiring is exercised elsewhere; here we assert the JSON
 * parse, guard against missing tenant / line / contributionId, and
 * confirm dispatch to {@link PremiumCessionService}.
 */
@ExtendWith(MockitoExtension.class)
class ReinsurancePremiumCessionConsumerTest {

    @Mock PremiumCessionService premiumCessionService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ReinsurancePremiumCessionConsumer consumer() {
        return new ReinsurancePremiumCessionConsumer(null, premiumCessionService, objectMapper);
    }

    @Test
    void processEvent_wellFormed_dispatchesToPremiumService() {
        UUID contributionId = UUID.randomUUID();
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
                """, contributionId, UUID.randomUUID(), tenantId);
        when(premiumCessionService.processPaidContribution(any(), anyString(), anyString()))
                .thenReturn(Flux.just(new Cession()));

        StepVerifier.create(consumer().processEvent(json))
                .verifyComplete();

        ArgumentCaptor<ContributionPaidEvent> cap = ArgumentCaptor.forClass(ContributionPaidEvent.class);
        verify(premiumCessionService).processPaidContribution(cap.capture(), anyString(), anyString());
        assertThat(cap.getValue().contributionId()).isEqualTo(contributionId);
        assertThat(cap.getValue().insuranceLine()).isEqualTo("HEALTH");
        assertThat(cap.getValue().amount()).isEqualByComparingTo("250.00");
        assertThat(cap.getValue().tenantId()).isEqualTo(tenantId);
    }

    @Test
    void processEvent_missingTenantId_skipsDispatch() {
        String json = """
                {"event":"CONTRIBUTION_PAID",
                 "contributionId":"11111111-1111-4111-8111-111111111111",
                 "amount":"250.00",
                 "currencyCode":"USD",
                 "insuranceLine":"HEALTH"}
                """;

        StepVerifier.create(consumer().processEvent(json))
                .verifyComplete();

        verify(premiumCessionService, never()).processPaidContribution(any(), anyString(), anyString());
    }

    @Test
    void processEvent_missingInsuranceLine_skipsDispatch() {
        String json = """
                {"event":"CONTRIBUTION_PAID",
                 "contributionId":"11111111-1111-4111-8111-111111111111",
                 "amount":"250.00",
                 "currencyCode":"USD",
                 "tenantId":"22222222-2222-4222-8222-222222222222"}
                """;

        StepVerifier.create(consumer().processEvent(json))
                .verifyComplete();

        verify(premiumCessionService, never()).processPaidContribution(any(), anyString(), anyString());
    }

    @Test
    void processEvent_prePhase6Payload_skipsGracefully() {
        // Pre-Phase-6 producer sent only contributionId/memberId/amount —
        // no line / tenant / currency. Consumer must not blow up; it just
        // has no cession to write.
        String json = """
                {"event":"CONTRIBUTION_PAID",
                 "contributionId":"11111111-1111-4111-8111-111111111111",
                 "memberId":"22222222-2222-4222-8222-222222222222",
                 "amount":"250.00"}
                """;

        StepVerifier.create(consumer().processEvent(json))
                .verifyComplete();

        verify(premiumCessionService, never()).processPaidContribution(any(), anyString(), anyString());
    }

    @Test
    void processEvent_malformedJson_errorsBubble() {
        StepVerifier.create(consumer().processEvent("{not json"))
                .expectError()
                .verify();

        verify(premiumCessionService, never()).processPaidContribution(any(), anyString(), anyString());
    }
}

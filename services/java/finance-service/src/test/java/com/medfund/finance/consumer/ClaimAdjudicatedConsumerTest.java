package com.medfund.finance.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.finance.entity.ProviderBalance;
import com.medfund.finance.service.ProviderBalanceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ClaimAdjudicatedConsumerTest {

    @Mock
    private ProviderBalanceService providerBalanceService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ClaimAdjudicatedConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new ClaimAdjudicatedConsumer(null, providerBalanceService, objectMapper);
    }

    @Test
    void processEvent_approvedClaim_updatesProviderBalance() {
        String providerId = UUID.randomUUID().toString();
        String json = """
            {"event":"CLAIM_ADJUDICATED","decision":"APPROVED","providerId":"%s","approvedAmount":"1500.00","currencyCode":"USD"}
            """.formatted(providerId);
        when(providerBalanceService.updateBalance(any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(Mono.just(new ProviderBalance()));

        StepVerifier.create(consumer.processEvent(json))
            .verifyComplete();

        verify(providerBalanceService).updateBalance(
            UUID.fromString(providerId),
            "USD",
            null,
            new BigDecimal("1500.00"),
            null,
            "system",
            "system@medfund"
        );
    }

    @Test
    void processEvent_partialApprovedClaim_updatesProviderBalance() {
        String providerId = UUID.randomUUID().toString();
        String json = """
            {"event":"CLAIM_ADJUDICATED","decision":"PARTIAL_APPROVED","providerId":"%s","approvedAmount":"950.00","currencyCode":"USD"}
            """.formatted(providerId);
        when(providerBalanceService.updateBalance(any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(Mono.just(new ProviderBalance()));

        StepVerifier.create(consumer.processEvent(json))
            .verifyComplete();

        verify(providerBalanceService).updateBalance(
            UUID.fromString(providerId),
            "USD",
            null,
            new BigDecimal("950.00"),
            null,
            "system",
            "system@medfund"
        );
    }

    @Test
    void processEvent_rejectedClaim_callsUpdateWithNullDeltas() {
        // Slice 2 of the finance plan: rejection still routes through
        // updateBalance so totalClaimed stays in sync if the event ever
        // carries claimedAmount. With null deltas it's a no-op write but
        // still touches the row's last_updated_at audit timestamp.
        String providerId = UUID.randomUUID().toString();
        String json = """
            {"event":"CLAIM_ADJUDICATED","decision":"REJECTED","providerId":"%s","claimedAmount":"500.00","approvedAmount":"0","currencyCode":"USD"}
            """.formatted(providerId);
        when(providerBalanceService.updateBalance(any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(Mono.just(new ProviderBalance()));

        StepVerifier.create(consumer.processEvent(json))
            .verifyComplete();

        verify(providerBalanceService).updateBalance(
            UUID.fromString(providerId),
            "USD",
            new BigDecimal("500.00"),
            null,
            null,
            "system",
            "system@medfund"
        );
    }

    @Test
    void processEvent_paidClaim_updatesPaidDelta() {
        String providerId = UUID.randomUUID().toString();
        String json = """
            {"event":"CLAIM_ADJUDICATED","decision":"PAID","providerId":"%s","approvedAmount":"750.00","currencyCode":"USD"}
            """.formatted(providerId);
        when(providerBalanceService.updateBalance(any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(Mono.just(new ProviderBalance()));

        StepVerifier.create(consumer.processEvent(json))
            .verifyComplete();

        verify(providerBalanceService).updateBalance(
            UUID.fromString(providerId),
            "USD",
            null,
            null,
            new BigDecimal("750.00"),
            "system",
            "system@medfund"
        );
    }

    @Test
    void processEvent_missingProviderContext_skips() {
        String json = """
            {"event":"CLAIM_ADJUDICATED","decision":"APPROVED","approvedAmount":"100","currencyCode":"USD"}
            """;
        StepVerifier.create(consumer.processEvent(json))
            .verifyComplete();

        verifyNoInteractions(providerBalanceService);
    }
}

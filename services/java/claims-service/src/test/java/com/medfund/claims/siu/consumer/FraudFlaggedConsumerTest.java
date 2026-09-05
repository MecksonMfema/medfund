package com.medfund.claims.siu.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.claims.siu.entity.FraudFlag;
import com.medfund.claims.siu.service.FraudFlagService;
import com.medfund.claims.siu.service.SiuCaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.ReceiverOptions;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class FraudFlaggedConsumerTest {

    private FraudFlagService flagService;
    private SiuCaseService caseService;
    private FraudFlaggedConsumer consumer;

    @BeforeEach
    void setUp() {
        flagService = mock(FraudFlagService.class);
        caseService = mock(SiuCaseService.class);
        consumer = new FraudFlaggedConsumer(
                (ReceiverOptions<String, String>) mock(ReceiverOptions.class),
                flagService,
                caseService,
                new ObjectMapper());
    }

    @Test
    void processEvent_happyPath_persistsFlagAndDispatchesTriage() {
        FraudFlag persisted = new FraudFlag();
        persisted.setId(UUID.randomUUID());
        persisted.setClaimId(UUID.randomUUID());
        when(flagService.persist(any())).thenReturn(Mono.just(persisted));
        when(caseService.evaluateTriage(any(FraudFlag.class))).thenReturn(Mono.empty());

        String json = """
                {
                  "eventType":"FRAUD_FLAG_EMITTED",
                  "tenantId":"t-1",
                  "claimId":"%s",
                  "modelVersion":"v1",
                  "riskScore":0.9,
                  "riskLevel":"HIGH",
                  "indicators":[],
                  "occurredAt":"2026-09-05T14:23:45Z"
                }
                """.formatted(UUID.randomUUID());

        StepVerifier.create(consumer.processEvent(json)).verifyComplete();
        verify(flagService).persist(any());
        verify(caseService).evaluateTriage(any(FraudFlag.class));
    }

    @Test
    void processEvent_malformedJson_returnsError() {
        StepVerifier.create(consumer.processEvent("{not-json"))
                .expectError()
                .verify();
        verify(flagService, never()).persist(any());
        verify(caseService, never()).evaluateTriage(any(FraudFlag.class));
    }

    @Test
    void processEvent_serviceErrorPropagates() {
        when(flagService.persist(any()))
                .thenReturn(Mono.error(new RuntimeException("db down")));
        String json = """
                {
                  "claimId":"%s","modelVersion":"v1","riskScore":0.9,
                  "riskLevel":"HIGH","indicators":[],
                  "occurredAt":"2026-09-05T14:23:45Z"
                }
                """.formatted(UUID.randomUUID());

        StepVerifier.create(consumer.processEvent(json))
                .expectError(RuntimeException.class)
                .verify();
        verify(caseService, never()).evaluateTriage(any(FraudFlag.class));
    }
}

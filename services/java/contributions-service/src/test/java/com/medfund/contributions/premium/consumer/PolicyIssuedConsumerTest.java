package com.medfund.contributions.premium.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.contributions.client.Ifrs17CohortClient;
import com.medfund.contributions.premium.service.EarningScheduleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.LinkedHashMap;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Wire-shape guard for the {@code medfund.user.policy-issued} consumer.
 * Verifies:
 * <ul>
 *   <li>Well-formed payloads flow through to
 *       {@link EarningScheduleService#writeSchedule(PolicyIssuedPayload)}.</li>
 *   <li>Payloads missing tenantId are dropped without delegation (they can't
 *       be scoped to a schema).</li>
 *   <li>Unparseable JSON is logged + swallowed rather than propagating.</li>
 *   <li>Downstream failures propagate — the outer consumer's own
 *       {@code onErrorResume} handles the ack path.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class PolicyIssuedConsumerTest {

    @Mock EarningScheduleService earningScheduleService;
    @Mock Ifrs17CohortClient ifrs17CohortClient;

    private PolicyIssuedPayloadParser parser;
    private PolicyIssuedConsumer consumer;

    @BeforeEach
    void setUp() {
        parser = new PolicyIssuedPayloadParser(new ObjectMapper());
        consumer = new PolicyIssuedConsumer(null, parser, earningScheduleService, ifrs17CohortClient);
    }

    @Test
    void processRecord_happyPath_delegatesToService() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        String json = new ObjectMapper().writeValueAsString(payloadMap(tenantId, policyId));
        when(earningScheduleService.writeSchedule(any(PolicyIssuedPayload.class))).thenReturn(Mono.empty());

        StepVerifier.create(consumer.processRecord(json)).verifyComplete();

        verify(earningScheduleService).writeSchedule(any(PolicyIssuedPayload.class));
    }

    @Test
    void processRecord_missingTenantId_isDropped() throws Exception {
        var body = payloadMap(UUID.randomUUID(), UUID.randomUUID());
        body.put("tenantId", "");
        String json = new ObjectMapper().writeValueAsString(body);

        StepVerifier.create(consumer.processRecord(json)).verifyComplete();

        verify(earningScheduleService, never()).writeSchedule(any());
    }

    @Test
    void processRecord_malformedJson_swallows() {
        StepVerifier.create(consumer.processRecord("{not json")).verifyComplete();

        verify(earningScheduleService, never()).writeSchedule(any());
    }

    @Test
    void processRecord_downstreamError_propagatesForOuterAckPath() throws Exception {
        String json = new ObjectMapper().writeValueAsString(payloadMap(UUID.randomUUID(), UUID.randomUUID()));
        when(earningScheduleService.writeSchedule(any(PolicyIssuedPayload.class)))
                .thenReturn(Mono.error(new IllegalStateException("db down")));

        // The consumer's flatMap chain relies on the error propagating up to the
        // outer onErrorResume that acks the offset (never .doOnTerminate — see
        // bug_reactor_kafka_ack_swallow). processRecord itself does not swallow.
        StepVerifier.create(consumer.processRecord(json))
                .expectError(IllegalStateException.class)
                .verify();
    }

    private static LinkedHashMap<String, String> payloadMap(UUID tenantId, UUID policyId) {
        LinkedHashMap<String, String> body = new LinkedHashMap<>();
        body.put("event", "POLICY_ISSUED");
        body.put("tenantId", tenantId.toString());
        body.put("policyId", policyId.toString());
        body.put("policySource", "LIFE_POLICY");
        body.put("insuranceLine", "LIFE");
        body.put("policyNumber", "POL-1");
        body.put("writtenPremium", "1200");
        body.put("currencyCode", "USD");
        body.put("coverageStart", "2026-01-01");
        body.put("coverageEnd", "2026-12-31");
        body.put("boundAt", "2026-01-01T00:00:00Z");
        body.put("memberId", UUID.randomUUID().toString());
        body.put("portfolioId", "");
        body.put("cohortId", "");
        body.put("renewedFromPolicyId", "");
        return body;
    }
}

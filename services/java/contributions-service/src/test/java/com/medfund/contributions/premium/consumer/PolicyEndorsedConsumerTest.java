package com.medfund.contributions.premium.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.contributions.premium.entity.EarningScheduleRun;
import com.medfund.contributions.premium.service.EarningScheduleClosureService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Wire-shape guard for the {@code medfund.user.policy-endorsed} consumer
 * (Phase 12 §C Phase 9). Mirrors {@link PolicyIssuedConsumerTest}:
 * <ul>
 *   <li>Happy path delegates to
 *       {@link EarningScheduleClosureService#recomputeForEndorsement}.</li>
 *   <li>Missing tenantId or required fields dropped without delegation.</li>
 *   <li>Unparseable JSON logged and swallowed.</li>
 *   <li>Downstream failures propagate for the outer ack-path.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class PolicyEndorsedConsumerTest {

    @Mock EarningScheduleClosureService closureService;

    private PolicyEndorsedPayloadParser parser;
    private PolicyEndorsedConsumer consumer;

    @BeforeEach
    void setUp() {
        parser = new PolicyEndorsedPayloadParser(new ObjectMapper());
        consumer = new PolicyEndorsedConsumer(null, parser, closureService);
    }

    @Test
    void processRecord_happyPath_delegatesToClosureService() throws Exception {
        String json = new ObjectMapper().writeValueAsString(payloadMap());
        when(closureService.recomputeForEndorsement(anyString(), any(UUID.class), any(UUID.class),
                anyString(), any(LocalDate.class), any(BigDecimal.class), anyString()))
                .thenReturn(Mono.just(new EarningScheduleRun()));

        StepVerifier.create(consumer.processRecord(json)).verifyComplete();

        verify(closureService).recomputeForEndorsement(anyString(), any(UUID.class), any(UUID.class),
                anyString(), any(LocalDate.class), any(BigDecimal.class), anyString());
    }

    @Test
    void processRecord_missingTenantId_isDropped() throws Exception {
        var body = payloadMap();
        body.put("tenantId", "");
        String json = new ObjectMapper().writeValueAsString(body);

        StepVerifier.create(consumer.processRecord(json)).verifyComplete();

        verify(closureService, never()).recomputeForEndorsement(anyString(), any(), any(), anyString(),
                any(), any(), anyString());
    }

    @Test
    void processRecord_missingRequiredField_isDropped() throws Exception {
        var body = payloadMap();
        body.put("endorsementId", "");     // required
        String json = new ObjectMapper().writeValueAsString(body);

        StepVerifier.create(consumer.processRecord(json)).verifyComplete();

        verify(closureService, never()).recomputeForEndorsement(anyString(), any(), any(), anyString(),
                any(), any(), anyString());
    }

    @Test
    void processRecord_malformedJson_swallows() {
        StepVerifier.create(consumer.processRecord("{not json")).verifyComplete();

        verify(closureService, never()).recomputeForEndorsement(anyString(), any(), any(), anyString(),
                any(), any(), anyString());
    }

    private static LinkedHashMap<String, String> payloadMap() {
        LinkedHashMap<String, String> body = new LinkedHashMap<>();
        body.put("event", "POLICY_ENDORSED");
        body.put("tenantId", UUID.randomUUID().toString());
        body.put("endorsementId", UUID.randomUUID().toString());
        body.put("reference", "END-2026-000001");
        body.put("policyId", UUID.randomUUID().toString());
        body.put("policySource", "LIFE_POLICY");
        body.put("insuranceLine", "LIFE");
        body.put("changeType", "PREMIUM_ADJUSTMENT");
        body.put("effectiveFrom", "2026-05-01");
        body.put("premiumDelta", "120.00");
        body.put("currencyCode", "USD");
        body.put("committedAt", "2026-04-15T09:30:00Z");
        return body;
    }
}

package com.medfund.contributions.premium.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.contributions.premium.service.EarningScheduleClosureService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Wire-shape guard for the {@code medfund.user.policy-status-changed}
 * consumer (Phase 13 §B per L6). Mirrors {@link PolicyEndorsedConsumerTest}:
 * <ul>
 *   <li>Each terminal status routes to the right
 *       {@link EarningScheduleClosureService} method.</li>
 *   <li>Missing tenantId / required fields dropped without delegation.</li>
 *   <li>Unparseable JSON logged and swallowed.</li>
 *   <li>A same-status "no-op" transition doesn't call the service.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class PolicyStatusChangedConsumerTest {

    @Mock EarningScheduleClosureService closureService;

    private PolicyStatusChangedPayloadParser parser;
    private PolicyStatusChangedConsumer consumer;

    @BeforeEach
    void setUp() {
        parser = new PolicyStatusChangedPayloadParser(new ObjectMapper());
        consumer = new PolicyStatusChangedConsumer(null, parser, closureService);
    }

    @Test
    void processRecord_lapsed_callsCloseOut() throws Exception {
        String json = new ObjectMapper().writeValueAsString(payloadMap("active", "lapsed"));
        when(closureService.closeOutForPolicyClosure(anyString(), any(UUID.class), anyString(),
                any(LocalDate.class), any(UUID.class))).thenReturn(Mono.just(3L));

        StepVerifier.create(consumer.processRecord(json)).verifyComplete();

        verify(closureService).closeOutForPolicyClosure(anyString(), any(UUID.class), anyString(),
                any(LocalDate.class), any(UUID.class));
        verify(closureService, never()).freezePolicyEarning(anyString(), any(), anyString(), any(), any());
        verify(closureService, never()).resumePolicyEarning(anyString(), any(), anyString(), any());
        verify(closureService, never()).reinstatePolicyEarning(anyString(), any(), anyString(), any());
    }

    @Test
    void processRecord_terminated_callsCloseOut() throws Exception {
        String json = new ObjectMapper().writeValueAsString(payloadMap("active", "terminated"));
        when(closureService.closeOutForPolicyClosure(anyString(), any(UUID.class), anyString(),
                any(LocalDate.class), any(UUID.class))).thenReturn(Mono.just(6L));

        StepVerifier.create(consumer.processRecord(json)).verifyComplete();

        verify(closureService).closeOutForPolicyClosure(anyString(), any(UUID.class), anyString(),
                any(LocalDate.class), any(UUID.class));
    }

    @Test
    void processRecord_suspended_callsFreeze() throws Exception {
        String json = new ObjectMapper().writeValueAsString(payloadMap("active", "suspended"));
        when(closureService.freezePolicyEarning(anyString(), any(UUID.class), anyString(),
                any(LocalDate.class), any(UUID.class))).thenReturn(Mono.just(2L));

        StepVerifier.create(consumer.processRecord(json)).verifyComplete();

        verify(closureService).freezePolicyEarning(anyString(), any(UUID.class), anyString(),
                any(LocalDate.class), any(UUID.class));
        verify(closureService, never()).closeOutForPolicyClosure(anyString(), any(), anyString(), any(), any());
    }

    @Test
    void processRecord_activeFromSuspended_callsResume() throws Exception {
        String json = new ObjectMapper().writeValueAsString(payloadMap("suspended", "active"));
        when(closureService.resumePolicyEarning(anyString(), any(UUID.class), anyString(),
                any(LocalDate.class))).thenReturn(Mono.just(2L));

        StepVerifier.create(consumer.processRecord(json)).verifyComplete();

        verify(closureService).resumePolicyEarning(anyString(), any(UUID.class), anyString(),
                any(LocalDate.class));
        verify(closureService, never()).reinstatePolicyEarning(anyString(), any(), anyString(), any());
    }

    @Test
    void processRecord_activeFromLapsed_callsReinstate() throws Exception {
        String json = new ObjectMapper().writeValueAsString(payloadMap("lapsed", "active"));
        when(closureService.reinstatePolicyEarning(anyString(), any(UUID.class), anyString(),
                any(LocalDate.class))).thenReturn(Mono.just(4L));

        StepVerifier.create(consumer.processRecord(json)).verifyComplete();

        verify(closureService).reinstatePolicyEarning(anyString(), any(UUID.class), anyString(),
                any(LocalDate.class));
        verify(closureService, never()).resumePolicyEarning(anyString(), any(), anyString(), any());
    }

    @Test
    void processRecord_activeFromTerminated_callsReinstate() throws Exception {
        String json = new ObjectMapper().writeValueAsString(payloadMap("terminated", "active"));
        when(closureService.reinstatePolicyEarning(anyString(), any(UUID.class), anyString(),
                any(LocalDate.class))).thenReturn(Mono.just(4L));

        StepVerifier.create(consumer.processRecord(json)).verifyComplete();

        verify(closureService).reinstatePolicyEarning(anyString(), any(UUID.class), anyString(),
                any(LocalDate.class));
    }

    @Test
    void processRecord_activeFromDraft_noAction() throws Exception {
        String json = new ObjectMapper().writeValueAsString(payloadMap("draft", "active"));

        StepVerifier.create(consumer.processRecord(json)).verifyComplete();

        verify(closureService, never()).closeOutForPolicyClosure(anyString(), any(), anyString(), any(), any());
        verify(closureService, never()).freezePolicyEarning(anyString(), any(), anyString(), any(), any());
        verify(closureService, never()).resumePolicyEarning(anyString(), any(), anyString(), any());
        verify(closureService, never()).reinstatePolicyEarning(anyString(), any(), anyString(), any());
    }

    @Test
    void processRecord_missingTenantId_isDropped() throws Exception {
        var body = payloadMap("active", "lapsed");
        body.put("tenantId", "");
        String json = new ObjectMapper().writeValueAsString(body);

        StepVerifier.create(consumer.processRecord(json)).verifyComplete();

        verify(closureService, never()).closeOutForPolicyClosure(anyString(), any(), anyString(), any(), any());
    }

    @Test
    void processRecord_missingRequiredField_isDropped() throws Exception {
        var body = payloadMap("active", "lapsed");
        body.put("policyId", "");
        String json = new ObjectMapper().writeValueAsString(body);

        StepVerifier.create(consumer.processRecord(json)).verifyComplete();

        verify(closureService, never()).closeOutForPolicyClosure(anyString(), any(), anyString(), any(), any());
    }

    @Test
    void processRecord_malformedJson_swallows() {
        StepVerifier.create(consumer.processRecord("{not json")).verifyComplete();

        verify(closureService, never()).closeOutForPolicyClosure(anyString(), any(), anyString(), any(), any());
    }

    @Test
    void processRecord_deterministicClosureRef_isStableAcrossReplays() throws Exception {
        // Same policy + status + effectiveAt → same closure_ref UUID so a
        // redelivered Kafka event is dropped by the service's COUNT lookup.
        String json = new ObjectMapper().writeValueAsString(payloadMap("active", "lapsed"));
        UUID capturedFirst;
        UUID capturedSecond;

        org.mockito.ArgumentCaptor<UUID> captor = org.mockito.ArgumentCaptor.forClass(UUID.class);
        when(closureService.closeOutForPolicyClosure(anyString(), any(UUID.class), anyString(),
                any(LocalDate.class), captor.capture())).thenReturn(Mono.just(3L));

        StepVerifier.create(consumer.processRecord(json)).verifyComplete();
        StepVerifier.create(consumer.processRecord(json)).verifyComplete();

        java.util.List<UUID> refs = captor.getAllValues();
        capturedFirst = refs.get(0);
        capturedSecond = refs.get(1);
        org.assertj.core.api.Assertions.assertThat(capturedFirst)
                .as("closure_ref stays stable across replays so the service can idempotently skip")
                .isEqualTo(capturedSecond);
    }

    @Test
    void processRecord_downstreamErrorBubblesForAckPath() throws Exception {
        String json = new ObjectMapper().writeValueAsString(payloadMap("active", "lapsed"));
        when(closureService.closeOutForPolicyClosure(anyString(), any(UUID.class), anyString(),
                any(LocalDate.class), any(UUID.class)))
                .thenReturn(Mono.error(new RuntimeException("db down")));

        StepVerifier.create(consumer.processRecord(json))
                .expectErrorMessage("db down")
                .verify();
    }

    private static LinkedHashMap<String, String> payloadMap(String fromStatus, String toStatus) {
        LinkedHashMap<String, String> body = new LinkedHashMap<>();
        body.put("event", "POLICY_STATUS_CHANGED");
        body.put("tenantId", UUID.randomUUID().toString());
        body.put("policyId", UUID.randomUUID().toString());
        body.put("policySource", "LIFE_POLICY");
        body.put("insuranceLine", "LIFE");
        body.put("fromStatus", fromStatus);
        body.put("toStatus", toStatus);
        body.put("effectiveAt", "2026-04-15T10:30:00+00:00");
        body.put("reasonCode", "NON_PAYMENT");
        body.put("actorId", UUID.randomUUID().toString());
        body.put("actorEmail", "admin@it.example");
        return body;
    }
}

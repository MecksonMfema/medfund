package com.medfund.finance.reinsurance.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.finance.reinsurance.entity.Cession;
import com.medfund.finance.reinsurance.entity.ReinsuranceReviewTask;
import com.medfund.finance.reinsurance.repository.CessionRepository;
import com.medfund.finance.reinsurance.service.CessionService;
import com.medfund.finance.reinsurance.service.ReinsuranceReviewTaskService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Focused unit test on {@link ReinsuranceLossCessionConsumer#processEvent}.
 * The Kafka Receiver wiring itself is exercised by an end-to-end
 * integration test; here we just assert the JSON parse, the
 * decision-filter, the tenant-context binding, dispatch to
 * {@link CessionService} for first-time cede, and Phase 8 regression
 * detection through {@link ReinsuranceReviewTaskService}.
 */
@ExtendWith(MockitoExtension.class)
class ReinsuranceLossCessionConsumerTest {

    @Mock CessionService cessionService;
    @Mock CessionRepository cessionRepository;
    @Mock ReinsuranceReviewTaskService reviewTaskService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ReinsuranceLossCessionConsumer consumer() {
        // receiverOptions=null: @PostConstruct is not invoked in tests, so no NPE.
        return new ReinsuranceLossCessionConsumer(
                null, cessionService, cessionRepository, reviewTaskService, objectMapper);
    }

    @Test
    void processEvent_approvedFirstTime_delegatesToCessionService() {
        UUID claimId = UUID.randomUUID();
        String tenantId = UUID.randomUUID().toString();
        String json = String.format("""
                {"event":"CLAIM_ADJUDICATED",
                 "claimId":"%s",
                 "claimNumber":"CLM-1",
                 "decision":"APPROVED",
                 "providerId":"%s",
                 "approvedAmount":"1000.00",
                 "currencyCode":"USD",
                 "insuranceLine":"HEALTH",
                 "memberId":"%s",
                 "payeeType":"PROVIDER",
                 "tenantId":"%s"}
                """,
                claimId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                tenantId);
        when(cessionRepository.findBySourceEventId(claimId)).thenReturn(Flux.empty());
        when(cessionService.processAdjudicatedClaim(any(), anyString(), anyString()))
                .thenReturn(Flux.just(new Cession()));

        StepVerifier.create(consumer().processEvent(json))
                .verifyComplete();

        ArgumentCaptor<com.medfund.finance.reinsurance.dto.ClaimAdjudicatedEvent> cap =
                ArgumentCaptor.forClass(com.medfund.finance.reinsurance.dto.ClaimAdjudicatedEvent.class);
        verify(cessionService).processAdjudicatedClaim(cap.capture(), anyString(), anyString());
        verify(reviewTaskService, never()).createRegressionTasks(
                any(), any(), any(), anyString(), anyString());
        assertThat(cap.getValue().claimId()).isEqualTo(claimId);
        assertThat(cap.getValue().insuranceLine()).isEqualTo("HEALTH");
        assertThat(cap.getValue().approvedAmount()).isEqualByComparingTo("1000.00");
        assertThat(cap.getValue().tenantId()).isEqualTo(tenantId);
    }

    @Test
    void processEvent_partialApprovedFirstTime_stillDispatches() {
        String json = baseEventJson("PARTIAL_APPROVED");
        UUID claimId = UUID.fromString("11111111-1111-4111-8111-111111111111");
        when(cessionRepository.findBySourceEventId(claimId)).thenReturn(Flux.empty());
        when(cessionService.processAdjudicatedClaim(any(), anyString(), anyString()))
                .thenReturn(Flux.empty());

        StepVerifier.create(consumer().processEvent(json))
                .verifyComplete();

        verify(cessionService).processAdjudicatedClaim(any(), anyString(), anyString());
    }

    @Test
    void processEvent_rejectedNoHistory_skipsBothPaths() {
        String json = baseEventJson("REJECTED");
        UUID claimId = UUID.fromString("11111111-1111-4111-8111-111111111111");
        when(cessionRepository.findBySourceEventId(claimId)).thenReturn(Flux.empty());

        StepVerifier.create(consumer().processEvent(json))
                .verifyComplete();

        verify(cessionService, never()).processAdjudicatedClaim(any(), anyString(), anyString());
        verify(reviewTaskService, never()).createRegressionTasks(
                any(), any(), any(), anyString(), anyString());
    }

    @Test
    void processEvent_readjudicatedLower_opensRegressionTask() {
        UUID claimId = UUID.fromString("11111111-1111-4111-8111-111111111111");
        String json = baseEventJson("APPROVED", "500.00");
        Cession prior = new Cession();
        prior.setId(UUID.randomUUID());
        prior.setCessionType("LOSS");
        prior.setStatus("ACTIVE");
        prior.setBasisAmount(new BigDecimal("1000.00"));
        prior.setCededAmount(new BigDecimal("300.00"));
        prior.setCurrencyCode("USD");
        prior.setSourceEventId(claimId);
        prior.setTreatyId(UUID.randomUUID());
        when(cessionRepository.findBySourceEventId(claimId)).thenReturn(Flux.just(prior));
        when(reviewTaskService.createRegressionTasks(eq(claimId), any(), any(),
                        anyString(), anyString()))
                .thenReturn(Flux.just(new ReinsuranceReviewTask()));

        StepVerifier.create(consumer().processEvent(json))
                .verifyComplete();

        ArgumentCaptor<BigDecimal> basisCap = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<List<Cession>> priorCap =
                (ArgumentCaptor<List<Cession>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(List.class);
        verify(reviewTaskService).createRegressionTasks(
                eq(claimId), priorCap.capture(), basisCap.capture(), anyString(), anyString());
        verify(cessionService, never()).processAdjudicatedClaim(any(), anyString(), anyString());
        assertThat(basisCap.getValue()).isEqualByComparingTo("500.00");
        assertThat(priorCap.getValue()).containsExactly(prior);
    }

    @Test
    void processEvent_readjudicatedHigher_isNoOp() {
        UUID claimId = UUID.fromString("11111111-1111-4111-8111-111111111111");
        String json = baseEventJson("APPROVED", "1500.00");
        Cession prior = new Cession();
        prior.setId(UUID.randomUUID());
        prior.setCessionType("LOSS");
        prior.setStatus("ACTIVE");
        prior.setBasisAmount(new BigDecimal("1000.00"));
        prior.setCededAmount(new BigDecimal("300.00"));
        prior.setCurrencyCode("USD");
        prior.setSourceEventId(claimId);
        prior.setTreatyId(UUID.randomUUID());
        when(cessionRepository.findBySourceEventId(claimId)).thenReturn(Flux.just(prior));

        StepVerifier.create(consumer().processEvent(json))
                .verifyComplete();

        verify(cessionService, never()).processAdjudicatedClaim(any(), anyString(), anyString());
        verify(reviewTaskService, never()).createRegressionTasks(
                any(), any(), any(), anyString(), anyString());
    }

    @Test
    void processEvent_readjudicatedRejectedWithHistory_opensRegressionTask() {
        UUID claimId = UUID.fromString("11111111-1111-4111-8111-111111111111");
        String json = baseEventJson("REJECTED");
        Cession prior = new Cession();
        prior.setId(UUID.randomUUID());
        prior.setCessionType("LOSS");
        prior.setStatus("ACTIVE");
        prior.setBasisAmount(new BigDecimal("500.00"));
        prior.setCededAmount(new BigDecimal("150.00"));
        prior.setCurrencyCode("USD");
        prior.setSourceEventId(claimId);
        prior.setTreatyId(UUID.randomUUID());
        when(cessionRepository.findBySourceEventId(claimId)).thenReturn(Flux.just(prior));
        when(reviewTaskService.createRegressionTasks(eq(claimId), any(), any(),
                        anyString(), anyString()))
                .thenReturn(Flux.just(new ReinsuranceReviewTask()));

        StepVerifier.create(consumer().processEvent(json))
                .verifyComplete();

        ArgumentCaptor<BigDecimal> basisCap = ArgumentCaptor.forClass(BigDecimal.class);
        verify(reviewTaskService).createRegressionTasks(
                eq(claimId), any(), basisCap.capture(), anyString(), anyString());
        assertThat(basisCap.getValue()).isEqualByComparingTo("0");
    }

    @Test
    void processEvent_missingTenantId_skipsDispatch() {
        String json = """
                {"event":"CLAIM_ADJUDICATED",
                 "claimId":"11111111-1111-4111-8111-111111111111",
                 "decision":"APPROVED",
                 "insuranceLine":"HEALTH",
                 "approvedAmount":"1000.00",
                 "currencyCode":"USD"}
                """;

        StepVerifier.create(consumer().processEvent(json))
                .verifyComplete();

        verify(cessionService, never()).processAdjudicatedClaim(any(), anyString(), anyString());
        verify(reviewTaskService, never()).createRegressionTasks(
                any(), any(), any(), anyString(), anyString());
    }

    @Test
    void processEvent_missingLine_skipsDispatch() {
        String json = """
                {"event":"CLAIM_ADJUDICATED",
                 "claimId":"11111111-1111-4111-8111-111111111111",
                 "decision":"APPROVED",
                 "approvedAmount":"1000.00",
                 "currencyCode":"USD",
                 "tenantId":"22222222-2222-4222-8222-222222222222"}
                """;

        StepVerifier.create(consumer().processEvent(json))
                .verifyComplete();

        verify(cessionService, never()).processAdjudicatedClaim(any(), anyString(), anyString());
        verify(reviewTaskService, never()).createRegressionTasks(
                any(), any(), any(), anyString(), anyString());
    }

    @Test
    void processEvent_malformedJson_errorsBubble() {
        StepVerifier.create(consumer().processEvent("{not json"))
                .expectError()
                .verify();

        verify(cessionService, never()).processAdjudicatedClaim(any(), anyString(), anyString());
    }

    private static String baseEventJson(String decision) {
        return baseEventJson(decision, "500.00");
    }

    private static String baseEventJson(String decision, String amount) {
        return String.format("""
                {"event":"CLAIM_ADJUDICATED",
                 "claimId":"11111111-1111-4111-8111-111111111111",
                 "claimNumber":"CLM-1",
                 "decision":"%s",
                 "providerId":"33333333-3333-4333-8333-333333333333",
                 "memberId":"44444444-4444-4444-8444-444444444444",
                 "approvedAmount":"%s",
                 "currencyCode":"USD",
                 "insuranceLine":"HEALTH",
                 "payeeType":"PROVIDER",
                 "tenantId":"22222222-2222-4222-8222-222222222222"}
                """, decision, amount);
    }
}

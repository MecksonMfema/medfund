package com.medfund.user.status;

import com.medfund.shared.lifecycle.PolicyReasonCode;
import com.medfund.user.entity.TravelPolicy;
import com.medfund.user.repository.TravelPolicyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TRAVEL_POLICY arm of the uniform contract. TRIP_CANCELLED is exclusive to
 * travel — and must be rejected on every other line (covered generically in
 * the base's cross-line rejection case).
 */
@ExtendWith(MockitoExtension.class)
class TravelPolicyStatusTransitionServiceTest extends PolicyStatusTransitionServiceTestBase<TravelPolicy> {

    @Mock private TravelPolicyRepository travelPolicyRepository;

    private TravelPolicyStatusTransitionService serviceInstance;

    @BeforeEach
    void wireService() {
        serviceInstance = new TravelPolicyStatusTransitionService(
                travelPolicyRepository, recorder, auditPublisher, policyStatusChangedPublisher, tx);
    }

    @Override protected R2dbcRepository<TravelPolicy, UUID> repository() { return travelPolicyRepository; }
    @Override protected PolicyStatusTransitionService<TravelPolicy> service() { return serviceInstance; }
    @Override protected String policySource() { return "TRAVEL_POLICY"; }
    @Override protected String insuranceLineExpected() { return "TRAVEL"; }
    @Override protected String foreignReasonCode() { return PolicyReasonCode.MORTALITY; }
    @Override protected TravelPolicy entity(String status) {
        var e = new TravelPolicy();
        e.setId(policyId);
        e.setStatus(status);
        e.setPolicyNumber("TP-" + policyId.toString().substring(0, 8));
        return e;
    }

    @Test
    void transition_tripCancelledTerminate_accepted() {
        var e = entity("active");
        when(travelPolicyRepository.findById(policyId)).thenReturn(Mono.just(e));
        when(travelPolicyRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service().transition(policyId, "terminate",
                        PolicyReasonCode.TRIP_CANCELLED, "itinerary cancelled", actorId, actorEmail))
                .assertNext(saved -> assertThat(saved.getStatus()).isEqualTo("terminated"))
                .verifyComplete();

        verify(recorder).recordPolicy(eq(policyId), eq("TRAVEL_POLICY"), eq("active"), eq("terminated"),
                any(OffsetDateTime.class), any(), eq(actorEmail),
                eq(PolicyReasonCode.TRIP_CANCELLED), anyString());
    }
}

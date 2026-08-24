package com.medfund.user.status;

import com.medfund.shared.lifecycle.PolicyReasonCode;
import com.medfund.user.entity.FuneralPolicy;
import com.medfund.user.repository.FuneralPolicyRepository;
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
 * FUNERAL_POLICY arm of the uniform contract. Funeral has NO MORTALITY arm —
 * INSURED_EVENT covers the claim-adjacent terminations instead.
 */
@ExtendWith(MockitoExtension.class)
class FuneralPolicyStatusTransitionServiceTest extends PolicyStatusTransitionServiceTestBase<FuneralPolicy> {

    @Mock private FuneralPolicyRepository funeralPolicyRepository;

    private FuneralPolicyStatusTransitionService serviceInstance;

    @BeforeEach
    void wireService() {
        serviceInstance = new FuneralPolicyStatusTransitionService(
                funeralPolicyRepository, recorder, auditPublisher, policyStatusChangedPublisher, tx);
    }

    @Override protected R2dbcRepository<FuneralPolicy, UUID> repository() { return funeralPolicyRepository; }
    @Override protected PolicyStatusTransitionService<FuneralPolicy> service() { return serviceInstance; }
    @Override protected String policySource() { return "FUNERAL_POLICY"; }
    @Override protected String insuranceLineExpected() { return "FUNERAL"; }
    @Override protected String foreignReasonCode() { return PolicyReasonCode.MORTALITY; }
    @Override protected FuneralPolicy entity(String status) {
        var e = new FuneralPolicy();
        e.setId(policyId);
        e.setStatus(status);
        e.setPolicyNumber("FP-" + policyId.toString().substring(0, 8));
        return e;
    }

    @Test
    void transition_insuredEventTerminate_accepted_mortalityRejected() {
        var e = entity("active");
        when(funeralPolicyRepository.findById(policyId)).thenReturn(Mono.just(e));
        when(funeralPolicyRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service().transition(policyId, "terminate",
                        PolicyReasonCode.INSURED_EVENT, "main member event", actorId, actorEmail))
                .assertNext(saved -> assertThat(saved.getStatus()).isEqualTo("terminated"))
                .verifyComplete();

        verify(recorder).recordPolicy(eq(policyId), eq("FUNERAL_POLICY"), eq("active"), eq("terminated"),
                any(OffsetDateTime.class), any(), eq(actorEmail),
                eq(PolicyReasonCode.INSURED_EVENT), anyString());
    }
}

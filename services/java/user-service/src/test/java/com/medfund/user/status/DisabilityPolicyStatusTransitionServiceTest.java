package com.medfund.user.status;

import com.medfund.shared.lifecycle.PolicyReasonCode;
import com.medfund.user.entity.DisabilityPolicy;
import com.medfund.user.repository.DisabilityPolicyRepository;
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
 * DISABILITY_POLICY arm of the uniform contract. RECOVERY is the
 * disability-specific reinstatement reason.
 */
@ExtendWith(MockitoExtension.class)
class DisabilityPolicyStatusTransitionServiceTest extends PolicyStatusTransitionServiceTestBase<DisabilityPolicy> {

    @Mock private DisabilityPolicyRepository disabilityPolicyRepository;

    private DisabilityPolicyStatusTransitionService serviceInstance;

    @BeforeEach
    void wireService() {
        serviceInstance = new DisabilityPolicyStatusTransitionService(
                disabilityPolicyRepository, recorder, auditPublisher, policyStatusChangedPublisher, tx);
    }

    @Override protected R2dbcRepository<DisabilityPolicy, UUID> repository() { return disabilityPolicyRepository; }
    @Override protected PolicyStatusTransitionService<DisabilityPolicy> service() { return serviceInstance; }
    @Override protected String policySource() { return "DISABILITY_POLICY"; }
    @Override protected String insuranceLineExpected() { return "DISABILITY"; }
    @Override protected String foreignReasonCode() { return PolicyReasonCode.MORTALITY; }
    @Override protected DisabilityPolicy entity(String status) {
        var d = new DisabilityPolicy();
        d.setId(policyId);
        d.setStatus(status);
        d.setPolicyNumber("DP-" + policyId.toString().substring(0, 8));
        return d;
    }

    @Test
    void transition_recoveryReinstate_accepted() {
        var d = entity("suspended");
        when(disabilityPolicyRepository.findById(policyId)).thenReturn(Mono.just(d));
        when(disabilityPolicyRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service().transition(policyId, "reinstate",
                        PolicyReasonCode.RECOVERY, "member recovered", actorId, actorEmail))
                .assertNext(saved -> assertThat(saved.getStatus()).isEqualTo("active"))
                .verifyComplete();

        verify(recorder).recordPolicy(eq(policyId), eq("DISABILITY_POLICY"), eq("suspended"), eq("active"),
                any(OffsetDateTime.class), any(), eq(actorEmail),
                eq(PolicyReasonCode.RECOVERY), anyString());
    }
}

package com.medfund.user.status;

import com.medfund.shared.lifecycle.PolicyReasonCode;
import com.medfund.user.entity.LifePolicy;
import com.medfund.user.repository.LifePolicyRepository;
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
 * LIFE_POLICY arm of the uniform policy-transition contract, plus the
 * line-specific vocab guard: MORTALITY is legal ONLY on life policies.
 */
@ExtendWith(MockitoExtension.class)
class LifePolicyStatusTransitionServiceTest extends PolicyStatusTransitionServiceTestBase<LifePolicy> {

    @Mock private LifePolicyRepository lifePolicyRepository;

    private LifePolicyStatusTransitionService serviceInstance;

    @BeforeEach
    void wireService() {
        // Base @BeforeEach ran first: identity tx + lenient collaborator stubs.
        serviceInstance = new LifePolicyStatusTransitionService(
                lifePolicyRepository, recorder, auditPublisher, policyStatusChangedPublisher, tx);
    }

    @Override protected R2dbcRepository<LifePolicy, UUID> repository() { return lifePolicyRepository; }
    @Override protected PolicyStatusTransitionService<LifePolicy> service() { return serviceInstance; }
    @Override protected String policySource() { return "LIFE_POLICY"; }
    @Override protected String insuranceLineExpected() { return "LIFE"; }
    @Override protected String foreignReasonCode() { return PolicyReasonCode.TRIP_CANCELLED; }
    @Override protected LifePolicy entity(String status) {
        var e = new LifePolicy();
        e.setId(policyId);
        e.setStatus(status);
        e.setPolicyNumber("LP-" + policyId.toString().substring(0, 8));
        return e;
    }

    @Test
    void transition_mortalityTerminate_lineSpecificVocab_accepted() {
        LifePolicy existing = entity("active");
        when(lifePolicyRepository.findById(policyId)).thenReturn(Mono.just(existing));
        when(lifePolicyRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service().transition(policyId, "terminate",
                        PolicyReasonCode.MORTALITY, "death certificate received", actorId, actorEmail))
                .assertNext(saved -> assertThat(saved.getStatus()).isEqualTo("terminated"))
                .verifyComplete();

        verify(recorder).recordPolicy(eq(policyId), eq("LIFE_POLICY"), eq("active"), eq("terminated"),
                any(OffsetDateTime.class), any(), eq(actorEmail),
                eq(PolicyReasonCode.MORTALITY), anyString());
    }
}

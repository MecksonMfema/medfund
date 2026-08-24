package com.medfund.user.status;

import com.medfund.shared.lifecycle.PolicyReasonCode;
import com.medfund.user.entity.Property;
import com.medfund.user.repository.PropertyRepository;
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
 * PROPERTY_POLICY arm of the uniform contract. SOLD is the property-specific
 * termination reason.
 */
@ExtendWith(MockitoExtension.class)
class PropertyPolicyStatusTransitionServiceTest extends PolicyStatusTransitionServiceTestBase<Property> {

    @Mock private PropertyRepository propertyRepository;

    private PropertyPolicyStatusTransitionService serviceInstance;

    @BeforeEach
    void wireService() {
        serviceInstance = new PropertyPolicyStatusTransitionService(
                propertyRepository, recorder, auditPublisher, policyStatusChangedPublisher, tx);
    }

    @Override protected R2dbcRepository<Property, UUID> repository() { return propertyRepository; }
    @Override protected PolicyStatusTransitionService<Property> service() { return serviceInstance; }
    @Override protected String policySource() { return "PROPERTY_POLICY"; }
    @Override protected String insuranceLineExpected() { return "PROPERTY"; }
    @Override protected String foreignReasonCode() { return PolicyReasonCode.MORTALITY; }
    @Override protected Property entity(String status) {
        var p = new Property();
        p.setId(policyId);
        p.setStatus(status);
        p.setPropertyName("PROP-" + policyId.toString().substring(0, 8));
        return p;
    }

    @Test
    void transition_soldTerminate_accepted() {
        var p = entity("active");
        when(propertyRepository.findById(policyId)).thenReturn(Mono.just(p));
        when(propertyRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service().transition(policyId, "terminate",
                        PolicyReasonCode.SOLD, "property sold", actorId, actorEmail))
                .assertNext(saved -> assertThat(saved.getStatus()).isEqualTo("terminated"))
                .verifyComplete();

        verify(recorder).recordPolicy(eq(policyId), eq("PROPERTY_POLICY"), eq("active"), eq("terminated"),
                any(OffsetDateTime.class), any(), eq(actorEmail),
                eq(PolicyReasonCode.SOLD), anyString());
    }
}

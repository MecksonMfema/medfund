package com.medfund.user.status;

import com.medfund.shared.lifecycle.PolicyReasonCode;
import com.medfund.user.entity.Vehicle;
import com.medfund.user.repository.VehicleRepository;
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
 * VEHICLE_POLICY arm of the uniform contract. TOTAL_LOSS / SOLD /
 * STORAGE_SUSPEND are vehicle-exclusive arms.
 */
@ExtendWith(MockitoExtension.class)
class VehiclePolicyStatusTransitionServiceTest extends PolicyStatusTransitionServiceTestBase<Vehicle> {

    @Mock private VehicleRepository vehicleRepository;

    private VehiclePolicyStatusTransitionService serviceInstance;

    @BeforeEach
    void wireService() {
        serviceInstance = new VehiclePolicyStatusTransitionService(
                vehicleRepository, recorder, auditPublisher, policyStatusChangedPublisher, tx);
    }

    @Override protected R2dbcRepository<Vehicle, UUID> repository() { return vehicleRepository; }
    @Override protected PolicyStatusTransitionService<Vehicle> service() { return serviceInstance; }
    @Override protected String policySource() { return "VEHICLE_POLICY"; }
    @Override protected String insuranceLineExpected() { return "VEHICLE"; }
    @Override protected String foreignReasonCode() { return PolicyReasonCode.MORTALITY; }
    @Override protected Vehicle entity(String status) {
        var v = new Vehicle();
        v.setId(policyId);
        v.setStatus(status);
        v.setRegistrationNumber("REG-" + policyId.toString().substring(0, 8));
        return v;
    }

    @Test
    void transition_totalLossTerminate_accepted() {
        var v = entity("active");
        when(vehicleRepository.findById(policyId)).thenReturn(Mono.just(v));
        when(vehicleRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service().transition(policyId, "terminate",
                        PolicyReasonCode.TOTAL_LOSS, "written off", actorId, actorEmail))
                .assertNext(saved -> assertThat(saved.getStatus()).isEqualTo("terminated"))
                .verifyComplete();

        verify(recorder).recordPolicy(eq(policyId), eq("VEHICLE_POLICY"), eq("active"), eq("terminated"),
                any(OffsetDateTime.class), any(), eq(actorEmail),
                eq(PolicyReasonCode.TOTAL_LOSS), anyString());
    }

    @Test
    void transition_storageSuspend_accepted() {
        var v = entity("active");
        when(vehicleRepository.findById(policyId)).thenReturn(Mono.just(v));
        when(vehicleRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service().transition(policyId, "suspend",
                        PolicyReasonCode.STORAGE_SUSPEND, "vehicle in storage", actorId, actorEmail))
                .assertNext(saved -> assertThat(saved.getStatus()).isEqualTo("suspended"))
                .verifyComplete();

        verify(recorder).recordPolicy(eq(policyId), eq("VEHICLE_POLICY"), eq("active"), eq("suspended"),
                any(OffsetDateTime.class), any(), eq(actorEmail),
                eq(PolicyReasonCode.STORAGE_SUSPEND), anyString());
    }
}

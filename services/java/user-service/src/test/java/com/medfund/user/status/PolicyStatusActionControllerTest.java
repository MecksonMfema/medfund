package com.medfund.user.status;

import com.medfund.user.entity.LifePolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Controller-layer coverage for the Phase 13 §A action surface: request
 * mapping, AuditActor extraction from the JWT, and status-code semantics
 * (204 / 400 / 404) without booting the security filter chain — the SQL seam
 * itself is covered by PolicyStatusHistoryWritesIT.
 */
@ExtendWith(MockitoExtension.class)
class PolicyStatusActionControllerTest {

    @Mock private LifePolicyStatusTransitionService lifeService;
    @Mock private FuneralPolicyStatusTransitionService funeralService;
    @Mock private DisabilityPolicyStatusTransitionService disabilityService;
    @Mock private TravelPolicyStatusTransitionService travelService;
    @Mock private VehiclePolicyStatusTransitionService vehicleService;
    @Mock private PropertyPolicyStatusTransitionService propertyService;

    private PolicyStatusActionController controller;

    private final UUID policyId = UUID.randomUUID();

    private final Jwt jwt = new Jwt(
            "token", Instant.now(), Instant.now().plusSeconds(300),
            Map.of("alg", "none"),
            Map.of("sub", "22222222-2222-4000-8000-000000000002",
                   "email", "admin@test.example"));

    @BeforeEach
    void setUp() {
        controller = new PolicyStatusActionController(
                lifeService, funeralService, disabilityService,
                travelService, vehicleService, propertyService);
    }

    @Test
    void lifeAction_lapse_returns204_andDelegatesWithJwtActor() {
        when(lifeService.transition(eq(policyId), eq("lapse"), eq("NON_PAYMENT"),
                eq("overdue"), anyString(), anyString()))
                .thenReturn(Mono.just(new LifePolicy()));

        StepVerifier.create(controller.lifeAction(policyId, "lapse",
                        new PolicyStatusActionController.ActionRequest("NON_PAYMENT", "overdue"), jwt))
                .assertNext(response ->
                        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT))
                .verifyComplete();

        verify(lifeService).transition(eq(policyId), eq("lapse"), eq("NON_PAYMENT"), eq("overdue"),
                eq("22222222-2222-4000-8000-000000000002"), eq("admin@test.example"));
    }

    @Test
    void lifeAction_invalidVocab_propagates400() {
        when(lifeService.transition(any(), anyString(), any(), any(), any(), any()))
                .thenReturn(Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Invalid reason_code for LIFE_POLICY: TRIP_CANCELLED")));

        StepVerifier.create(controller.lifeAction(policyId, "lapse",
                        new PolicyStatusActionController.ActionRequest("TRIP_CANCELLED", null), jwt))
                .expectErrorSatisfies(e -> {
                    var rse = (ResponseStatusException) e;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(rse.getReason()).contains("TRIP_CANCELLED");
                })
                .verify();
    }

    @Test
    void lifeAction_unknownPolicy_propagates404() {
        when(lifeService.transition(any(), anyString(), any(), any(), any(), any()))
                .thenReturn(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Policy not found: " + policyId)));

        StepVerifier.create(controller.lifeAction(policyId, "reinstate",
                        new PolicyStatusActionController.ActionRequest(null, null), null))
                .expectErrorSatisfies(e ->
                        assertThat(((ResponseStatusException) e).getStatusCode())
                                .isEqualTo(HttpStatus.NOT_FOUND))
                .verify();
    }

    @Test
    void allSixEndpoints_delegateToTheirLine() {
        for (var mocked : new PolicyStatusTransitionService<?>[]{
                funeralService, disabilityService, travelService, vehicleService, propertyService}) {
            // Each mock returns an entity Mono; the exact type is opaque to the controller.
            when(mocked.transition(any(), anyString(), any(), any(), any(), any()))
                    .thenAnswer(inv -> Mono.just(new Object()));
        }

        var req = new PolicyStatusActionController.ActionRequest("ADMIN_CORRECTION", "fix");
        StepVerifier.create(controller.funeralAction(policyId, "suspend", req, jwt))
                .expectNextCount(1).verifyComplete();
        StepVerifier.create(controller.disabilityAction(policyId, "suspend", req, jwt))
                .expectNextCount(1).verifyComplete();
        StepVerifier.create(controller.travelAction(policyId, "reinstate", req, jwt))
                .expectNextCount(1).verifyComplete();
        StepVerifier.create(controller.vehicleAction(policyId, "terminate", req, jwt))
                .expectNextCount(1).verifyComplete();
        StepVerifier.create(controller.propertyAction(policyId, "lapse", req, jwt))
                .expectNextCount(1).verifyComplete();

        verify(funeralService).transition(eq(policyId), eq("suspend"), any(), any(), any(), any());
        verify(disabilityService).transition(eq(policyId), eq("suspend"), any(), any(), any(), any());
        verify(travelService).transition(eq(policyId), eq("reinstate"), any(), any(), any(), any());
        verify(vehicleService).transition(eq(policyId), eq("terminate"), any(), any(), any(), any());
        verify(propertyService).transition(eq(policyId), eq("lapse"), any(), any(), any(), any());
    }
}

package com.medfund.user.status;

import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.security.Permissions;
import com.medfund.shared.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Phase 13 §A per L4: the uniform admin surface for annual-bind policy
 * status actions. Six lines × four actions ({@code lapse / terminate /
 * suspend / reinstate}), one shape each — the per-line differences (entity,
 * reason vocab) live behind the {@link PolicyStatusTransitionService}
 * subclasses, not in this controller.
 *
 * <p>204 on success, 400 on unknown action or out-of-vocab reason code,
 * 404 when the policy doesn't exist. The policy-status-changed Kafka event
 * is deferred to §B Phase 5; the AUDIT event fires inside the service.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Policy status actions",
        description = "Phase 13 §A: lapse / terminate / suspend / reinstate for annual-bind policies")
@SecurityRequirement(name = "bearer-jwt")
public class PolicyStatusActionController {

    private final LifePolicyStatusTransitionService lifeService;
    private final FuneralPolicyStatusTransitionService funeralService;
    private final DisabilityPolicyStatusTransitionService disabilityService;
    private final TravelPolicyStatusTransitionService travelService;
    private final VehiclePolicyStatusTransitionService vehicleService;
    private final PropertyPolicyStatusTransitionService propertyService;

    public record ActionRequest(String reasonCode, String reasonNote) {}

    @PostMapping("/api/v1/life-policies/{id}/{action}")
    @RequiresPermission(Permissions.POLICY_STATUS_MANAGE)
    @Operation(summary = "Life policy status action (lapse | terminate | suspend | reinstate)")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Transition applied"),
            @ApiResponse(responseCode = "400", description = "Unknown action or invalid reason_code for LIFE_POLICY"),
            @ApiResponse(responseCode = "404", description = "Policy not found")
    })
    public Mono<ResponseEntity<Void>> lifeAction(@PathVariable UUID id,
                                                 @PathVariable String action,
                                                 @RequestBody ActionRequest req,
                                                 @AuthenticationPrincipal Jwt jwt) {
        return dispatch(lifeService, id, action, req, jwt);
    }

    @PostMapping("/api/v1/funeral-policies/{id}/{action}")
    @RequiresPermission(Permissions.POLICY_STATUS_MANAGE)
    @Operation(summary = "Funeral policy status action (lapse | terminate | suspend | reinstate)")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Transition applied"),
            @ApiResponse(responseCode = "400", description = "Unknown action or invalid reason_code for FUNERAL_POLICY"),
            @ApiResponse(responseCode = "404", description = "Policy not found")
    })
    public Mono<ResponseEntity<Void>> funeralAction(@PathVariable UUID id,
                                                    @PathVariable String action,
                                                    @RequestBody ActionRequest req,
                                                    @AuthenticationPrincipal Jwt jwt) {
        return dispatch(funeralService, id, action, req, jwt);
    }

    @PostMapping("/api/v1/disability-policies/{id}/{action}")
    @RequiresPermission(Permissions.POLICY_STATUS_MANAGE)
    @Operation(summary = "Disability policy status action (lapse | terminate | suspend | reinstate)")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Transition applied"),
            @ApiResponse(responseCode = "400", description = "Unknown action or invalid reason_code for DISABILITY_POLICY"),
            @ApiResponse(responseCode = "404", description = "Policy not found")
    })
    public Mono<ResponseEntity<Void>> disabilityAction(@PathVariable UUID id,
                                                       @PathVariable String action,
                                                       @RequestBody ActionRequest req,
                                                       @AuthenticationPrincipal Jwt jwt) {
        return dispatch(disabilityService, id, action, req, jwt);
    }

    @PostMapping("/api/v1/travel-policies/{id}/{action}")
    @RequiresPermission(Permissions.POLICY_STATUS_MANAGE)
    @Operation(summary = "Travel policy status action (lapse | terminate | suspend | reinstate)")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Transition applied"),
            @ApiResponse(responseCode = "400", description = "Unknown action or invalid reason_code for TRAVEL_POLICY"),
            @ApiResponse(responseCode = "404", description = "Policy not found")
    })
    public Mono<ResponseEntity<Void>> travelAction(@PathVariable UUID id,
                                                   @PathVariable String action,
                                                   @RequestBody ActionRequest req,
                                                   @AuthenticationPrincipal Jwt jwt) {
        return dispatch(travelService, id, action, req, jwt);
    }

    @PostMapping("/api/v1/vehicle-policies/{id}/{action}")
    @RequiresPermission(Permissions.POLICY_STATUS_MANAGE)
    @Operation(summary = "Vehicle policy status action (lapse | terminate | suspend | reinstate)")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Transition applied"),
            @ApiResponse(responseCode = "400", description = "Unknown action or invalid reason_code for VEHICLE_POLICY"),
            @ApiResponse(responseCode = "404", description = "Policy not found")
    })
    public Mono<ResponseEntity<Void>> vehicleAction(@PathVariable UUID id,
                                                    @PathVariable String action,
                                                    @RequestBody ActionRequest req,
                                                    @AuthenticationPrincipal Jwt jwt) {
        return dispatch(vehicleService, id, action, req, jwt);
    }

    @PostMapping("/api/v1/property-policies/{id}/{action}")
    @RequiresPermission(Permissions.POLICY_STATUS_MANAGE)
    @Operation(summary = "Property policy status action (lapse | terminate | suspend | reinstate)")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Transition applied"),
            @ApiResponse(responseCode = "400", description = "Unknown action or invalid reason_code for PROPERTY_POLICY"),
            @ApiResponse(responseCode = "404", description = "Policy not found")
    })
    public Mono<ResponseEntity<Void>> propertyAction(@PathVariable UUID id,
                                                     @PathVariable String action,
                                                     @RequestBody ActionRequest req,
                                                     @AuthenticationPrincipal Jwt jwt) {
        return dispatch(propertyService, id, action, req, jwt);
    }

    private Mono<ResponseEntity<Void>> dispatch(PolicyStatusTransitionService<?> service,
                                                UUID id, String action, ActionRequest req,
                                                Jwt jwt) {
        ActionRequest body = req != null ? req : new ActionRequest(null, null);
        return service.transition(id, action, body.reasonCode(), body.reasonNote(),
                        AuditActor.id(jwt), AuditActor.email(jwt))
                .thenReturn(ResponseEntity.status(HttpStatus.NO_CONTENT).<Void>build());
    }
}

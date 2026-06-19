package com.medfund.tenancy.controller;

import com.medfund.shared.audit.AuditActor;
import com.medfund.tenancy.dto.CreatePlanRequest;
import com.medfund.tenancy.dto.PlanResponse;
import com.medfund.tenancy.service.PlanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/plans")
@Tag(name = "Plans", description = "Subscription plan management — super admin only")
@SecurityRequirement(name = "bearer-jwt")
public class PlanController {

    private final PlanService planService;

    public PlanController(PlanService planService) {
        this.planService = planService;
    }

    @GetMapping
    @Operation(summary = "List active plans", description = "Returns all active subscription plans ordered by price ascending.")
    @ApiResponse(responseCode = "200", description = "List of active plans")
    public Flux<PlanResponse> findAllActive() {
        return planService.findAllActive().map(PlanResponse::from);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get plan by ID")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Plan found"),
            @ApiResponse(responseCode = "404", description = "Plan not found")
    })
    public Mono<PlanResponse> findById(
            @Parameter(description = "Plan UUID") @PathVariable UUID id) {
        return planService.findById(id).map(PlanResponse::from);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new plan", description = "Creates a new subscription plan for tenants.")
    @ApiResponse(responseCode = "201", description = "Plan created")
    public Mono<PlanResponse> create(
            @Valid @RequestBody CreatePlanRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return planService.create(request, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(PlanResponse::from);
    }

    @PostMapping("/{id}/deactivate")
    @Operation(summary = "Deactivate plan", description = "Deactivates a plan. Existing tenants on this plan are not affected.")
    @ApiResponse(responseCode = "200", description = "Plan deactivated")
    public Mono<PlanResponse> deactivate(
            @Parameter(description = "Plan UUID") @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt) {
        return planService.deactivate(id, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(PlanResponse::from);
    }
}

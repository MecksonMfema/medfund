package com.medfund.user.controller;

import com.medfund.shared.audit.AuditActor;
import com.medfund.user.dto.CreateTravelPolicyRequest;
import com.medfund.user.dto.TravelPolicyResponse;
import com.medfund.user.dto.UpdateTravelPolicyRequest;
import com.medfund.user.service.TravelPolicyService;
import io.swagger.v3.oas.annotations.Operation;
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
@RequestMapping("/api/v1/travel-policies")
@Tag(name = "Travel Policies", description = "Travel insurance policy management — lifecycle + billing overrides")
@SecurityRequirement(name = "bearer-jwt")
public class TravelPolicyController {

    private final TravelPolicyService travelPolicyService;

    public TravelPolicyController(TravelPolicyService travelPolicyService) {
        this.travelPolicyService = travelPolicyService;
    }

    @GetMapping
    @Operation(summary = "List travel policies")
    public Flux<TravelPolicyResponse> findAll() {
        return travelPolicyService.findAll().map(TravelPolicyResponse::from);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get travel policy by ID")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Travel policy found"),
            @ApiResponse(responseCode = "404", description = "Travel policy not found")
    })
    public Mono<TravelPolicyResponse> findById(@PathVariable UUID id) {
        return travelPolicyService.findById(id).map(TravelPolicyResponse::from);
    }

    @GetMapping("/scheme/{schemeId}")
    @Operation(summary = "List travel policies by scheme")
    public Flux<TravelPolicyResponse> findBySchemeId(@PathVariable UUID schemeId) {
        return travelPolicyService.findBySchemeId(schemeId).map(TravelPolicyResponse::from);
    }

    @GetMapping("/member/{travelerMemberId}")
    @Operation(summary = "List travel policies by traveler member")
    public Flux<TravelPolicyResponse> findByTravelerMemberId(@PathVariable UUID travelerMemberId) {
        return travelPolicyService.findByTravelerMemberId(travelerMemberId).map(TravelPolicyResponse::from);
    }

    @GetMapping("/search")
    @Operation(summary = "Search travel policies by policy number")
    public Flux<TravelPolicyResponse> search(@RequestParam String q) {
        return travelPolicyService.search(q).map(TravelPolicyResponse::from);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Register a new travel policy")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Travel policy created"),
            @ApiResponse(responseCode = "400", description = "Validation error")
    })
    public Mono<TravelPolicyResponse> create(@Valid @RequestBody CreateTravelPolicyRequest request,
                                              @AuthenticationPrincipal Jwt jwt) {
        return travelPolicyService.create(request, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(TravelPolicyResponse::from);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update travel policy details")
    public Mono<TravelPolicyResponse> update(@PathVariable UUID id,
                                              @Valid @RequestBody UpdateTravelPolicyRequest request,
                                              @AuthenticationPrincipal Jwt jwt) {
        return travelPolicyService.update(id, request, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(TravelPolicyResponse::from);
    }

    @PostMapping("/{id}/suspend")
    @Operation(summary = "Suspend travel policy")
    public Mono<TravelPolicyResponse> suspend(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return travelPolicyService.suspend(id, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(TravelPolicyResponse::from);
    }

    @PostMapping("/{id}/terminate")
    @Operation(summary = "Terminate travel policy")
    public Mono<TravelPolicyResponse> terminate(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return travelPolicyService.terminate(id, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(TravelPolicyResponse::from);
    }

    @PostMapping("/{id}/clear-billing-override")
    @Operation(summary = "Clear the travel policy's per-asset pricing override",
            description = "Nulls billing_override_amount, reason, and effective_from so billing falls back to scheme defaults.")
    public Mono<TravelPolicyResponse> clearBillingOverride(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return travelPolicyService.clearBillingOverride(id, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(TravelPolicyResponse::from);
    }
}

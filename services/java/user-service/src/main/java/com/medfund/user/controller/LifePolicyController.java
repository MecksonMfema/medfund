package com.medfund.user.controller;

import com.medfund.shared.audit.AuditActor;
import com.medfund.user.dto.CreateLifePolicyRequest;
import com.medfund.user.dto.LifePolicyResponse;
import com.medfund.user.dto.UpdateLifePolicyRequest;
import com.medfund.user.service.LifePolicyService;
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
@RequestMapping("/api/v1/life-policies")
@Tag(name = "Life Policies", description = "Life insurance policy management — lifecycle + billing overrides")
@SecurityRequirement(name = "bearer-jwt")
public class LifePolicyController {

    private final LifePolicyService lifePolicyService;

    public LifePolicyController(LifePolicyService lifePolicyService) {
        this.lifePolicyService = lifePolicyService;
    }

    @GetMapping
    @Operation(summary = "List life policies")
    public Flux<LifePolicyResponse> findAll() {
        return lifePolicyService.findAll().map(LifePolicyResponse::from);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get life policy by ID")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Life policy found"),
            @ApiResponse(responseCode = "404", description = "Life policy not found")
    })
    public Mono<LifePolicyResponse> findById(@PathVariable UUID id) {
        return lifePolicyService.findById(id).map(LifePolicyResponse::from);
    }

    @GetMapping("/scheme/{schemeId}")
    @Operation(summary = "List life policies by scheme")
    public Flux<LifePolicyResponse> findBySchemeId(@PathVariable UUID schemeId) {
        return lifePolicyService.findBySchemeId(schemeId).map(LifePolicyResponse::from);
    }

    @GetMapping("/member/{insuredMemberId}")
    @Operation(summary = "List life policies by insured member")
    public Flux<LifePolicyResponse> findByInsuredMemberId(@PathVariable UUID insuredMemberId) {
        return lifePolicyService.findByInsuredMemberId(insuredMemberId).map(LifePolicyResponse::from);
    }

    @GetMapping("/search")
    @Operation(summary = "Search life policies by policy number")
    public Flux<LifePolicyResponse> search(@RequestParam String q) {
        return lifePolicyService.search(q).map(LifePolicyResponse::from);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Register a new life policy")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Life policy created"),
            @ApiResponse(responseCode = "400", description = "Validation error")
    })
    public Mono<LifePolicyResponse> create(@Valid @RequestBody CreateLifePolicyRequest request,
                                            @AuthenticationPrincipal Jwt jwt) {
        return lifePolicyService.create(request, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(LifePolicyResponse::from);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update life policy details")
    public Mono<LifePolicyResponse> update(@PathVariable UUID id,
                                            @Valid @RequestBody UpdateLifePolicyRequest request,
                                            @AuthenticationPrincipal Jwt jwt) {
        return lifePolicyService.update(id, request, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(LifePolicyResponse::from);
    }

    @PostMapping("/{id}/suspend")
    @Operation(summary = "Suspend life policy")
    public Mono<LifePolicyResponse> suspend(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return lifePolicyService.suspend(id, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(LifePolicyResponse::from);
    }

    @PostMapping("/{id}/terminate")
    @Operation(summary = "Terminate life policy")
    public Mono<LifePolicyResponse> terminate(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return lifePolicyService.terminate(id, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(LifePolicyResponse::from);
    }

    @PostMapping("/{id}/clear-billing-override")
    @Operation(summary = "Clear the life policy's per-policy pricing override",
            description = "Nulls billing_override_amount, reason, and effective_from so billing falls back to scheme defaults.")
    public Mono<LifePolicyResponse> clearBillingOverride(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return lifePolicyService.clearBillingOverride(id, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(LifePolicyResponse::from);
    }
}

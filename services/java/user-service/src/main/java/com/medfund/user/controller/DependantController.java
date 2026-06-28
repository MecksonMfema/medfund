package com.medfund.user.controller;

import com.medfund.shared.audit.AuditActor;
import com.medfund.user.dto.CreateDependantRequest;
import com.medfund.user.dto.DependantResponse;
import com.medfund.user.dto.UpdateDependantRequest;
import com.medfund.user.service.DependantService;
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
@RequestMapping("/api/v1/dependants")
@Tag(name = "Dependants", description = "Manage member dependants")
@SecurityRequirement(name = "bearer-jwt")
public class DependantController {

    private final DependantService dependantService;

    public DependantController(DependantService dependantService) {
        this.dependantService = dependantService;
    }

    @GetMapping("/member/{memberId}")
    @Operation(summary = "List dependants for a member")
    public Flux<DependantResponse> findByMemberId(@PathVariable UUID memberId) {
        return dependantService.findByMemberId(memberId).map(DependantResponse::from);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get dependant by ID")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Dependant found"),
        @ApiResponse(responseCode = "404", description = "Dependant not found")
    })
    public Mono<DependantResponse> findById(@PathVariable UUID id) {
        return dependantService.findById(id).map(DependantResponse::from);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a dependant to a member")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Dependant created"),
        @ApiResponse(responseCode = "400", description = "Validation error")
    })
    public Mono<DependantResponse> create(@Valid @RequestBody CreateDependantRequest request, @AuthenticationPrincipal Jwt jwt) {
        return dependantService.create(request, AuditActor.id(jwt), AuditActor.email(jwt)).map(DependantResponse::from);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update an existing dependant",
               description = "Partial update — null fields are left unchanged.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Dependant updated"),
        @ApiResponse(responseCode = "404", description = "Dependant not found")
    })
    public Mono<DependantResponse> update(@PathVariable UUID id,
                                          @Valid @RequestBody UpdateDependantRequest request,
                                          @AuthenticationPrincipal Jwt jwt) {
        return dependantService.update(id, request, AuditActor.id(jwt), AuditActor.email(jwt)).map(DependantResponse::from);
    }

    @PostMapping("/{id}/remove")
    @Operation(summary = "Remove a dependant", description = "Soft-removes dependant by setting status to 'removed'")
    public Mono<DependantResponse> remove(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return dependantService.remove(id, AuditActor.id(jwt), AuditActor.email(jwt)).map(DependantResponse::from);
    }

    @PostMapping("/{id}/clear-billing-override")
    @Operation(summary = "Clear the dependant's per-person pricing override",
        description = "Nulls billing_override_amount, reason, and effective_from so billing falls back to the " +
                      "age-group price. Mirrors POST /members/{id}/clear-billing-override.")
    public Mono<DependantResponse> clearBillingOverride(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return dependantService.clearBillingOverride(id, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(DependantResponse::from);
    }
}

package com.medfund.user.controller;

import com.medfund.shared.audit.AuditActor;
import com.medfund.user.dto.CreateDisabilityPolicyRequest;
import com.medfund.user.dto.DisabilityPolicyFilterParams;
import com.medfund.user.dto.DisabilityPolicyResponse;
import com.medfund.user.dto.DisabilityPolicyRow;
import com.medfund.user.dto.PageResponse;
import com.medfund.user.dto.UpdateDisabilityPolicyRequest;
import com.medfund.user.service.DisabilityPolicyService;
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
@RequestMapping("/api/v1/disability-policies")
@Tag(name = "Disability Policies", description = "Disability income insurance policy management — lifecycle + billing overrides")
@SecurityRequirement(name = "bearer-jwt")
public class DisabilityPolicyController {

    private final DisabilityPolicyService disabilityPolicyService;

    public DisabilityPolicyController(DisabilityPolicyService disabilityPolicyService) {
        this.disabilityPolicyService = disabilityPolicyService;
    }

    @GetMapping
    @Operation(summary = "List disability policies (unpaginated — prefer /page)")
    public Flux<DisabilityPolicyResponse> findAll() {
        return disabilityPolicyService.findAll().map(DisabilityPolicyResponse::from);
    }

    @GetMapping("/page")
    @Operation(summary = "List disability policies (server-side paginated)",
            description = "Rows carry pre-joined scheme + insured-member names.")
    public Mono<PageResponse<DisabilityPolicyRow>> page(@RequestParam(required = false) String status,
                                                         @RequestParam(required = false) UUID schemeId,
                                                         @RequestParam(required = false) String q,
                                                         @RequestParam(required = false) String sortKey,
                                                         @RequestParam(required = false) String sortDirection,
                                                         @RequestParam(defaultValue = "0") int page,
                                                         @RequestParam(defaultValue = "50") int size) {
        return disabilityPolicyService.searchPaged(new DisabilityPolicyFilterParams(
                status, schemeId, q, sortKey, sortDirection, page, size));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get disability policy by ID")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Disability policy found"),
            @ApiResponse(responseCode = "404", description = "Disability policy not found")
    })
    public Mono<DisabilityPolicyResponse> findById(@PathVariable UUID id) {
        return disabilityPolicyService.findById(id).map(DisabilityPolicyResponse::from);
    }

    @GetMapping("/scheme/{schemeId}")
    @Operation(summary = "List disability policies by scheme")
    public Flux<DisabilityPolicyResponse> findBySchemeId(@PathVariable UUID schemeId) {
        return disabilityPolicyService.findBySchemeId(schemeId).map(DisabilityPolicyResponse::from);
    }

    @GetMapping("/member/{insuredMemberId}")
    @Operation(summary = "List disability policies by insured member")
    public Flux<DisabilityPolicyResponse> findByInsuredMemberId(@PathVariable UUID insuredMemberId) {
        return disabilityPolicyService.findByInsuredMemberId(insuredMemberId).map(DisabilityPolicyResponse::from);
    }

    @GetMapping("/search")
    @Operation(summary = "Search disability policies by policy number")
    public Flux<DisabilityPolicyResponse> search(@RequestParam String q) {
        return disabilityPolicyService.search(q).map(DisabilityPolicyResponse::from);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Register a new disability policy")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Disability policy created"),
            @ApiResponse(responseCode = "400", description = "Validation error")
    })
    public Mono<DisabilityPolicyResponse> create(@Valid @RequestBody CreateDisabilityPolicyRequest request,
                                                  @AuthenticationPrincipal Jwt jwt) {
        return disabilityPolicyService.create(request, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(DisabilityPolicyResponse::from);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update disability policy details")
    public Mono<DisabilityPolicyResponse> update(@PathVariable UUID id,
                                                  @Valid @RequestBody UpdateDisabilityPolicyRequest request,
                                                  @AuthenticationPrincipal Jwt jwt) {
        return disabilityPolicyService.update(id, request, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(DisabilityPolicyResponse::from);
    }

    @PostMapping("/{id}/suspend")
    @Operation(summary = "Suspend disability policy")
    public Mono<DisabilityPolicyResponse> suspend(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return disabilityPolicyService.suspend(id, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(DisabilityPolicyResponse::from);
    }

    @PostMapping("/{id}/terminate")
    @Operation(summary = "Terminate disability policy")
    public Mono<DisabilityPolicyResponse> terminate(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return disabilityPolicyService.terminate(id, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(DisabilityPolicyResponse::from);
    }

    @PostMapping("/{id}/clear-billing-override")
    @Operation(summary = "Clear the disability policy's per-policy pricing override",
            description = "Nulls billing_override_amount, reason, and effective_from so billing falls back to scheme defaults.")
    public Mono<DisabilityPolicyResponse> clearBillingOverride(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return disabilityPolicyService.clearBillingOverride(id, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(DisabilityPolicyResponse::from);
    }
}

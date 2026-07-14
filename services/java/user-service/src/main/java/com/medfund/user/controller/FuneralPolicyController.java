package com.medfund.user.controller;

import com.medfund.shared.audit.AuditActor;
import com.medfund.user.dto.CreateFuneralPolicyRequest;
import com.medfund.user.dto.FuneralPolicyFilterParams;
import com.medfund.user.dto.FuneralPolicyResponse;
import com.medfund.user.dto.FuneralPolicyRow;
import com.medfund.user.dto.PageResponse;
import com.medfund.user.dto.UpdateFuneralPolicyRequest;
import com.medfund.user.service.FuneralPolicyService;
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
@RequestMapping("/api/v1/funeral-policies")
@Tag(name = "Funeral Policies", description = "Funeral insurance policy management — lifecycle + billing overrides")
@SecurityRequirement(name = "bearer-jwt")
public class FuneralPolicyController {

    private final FuneralPolicyService funeralPolicyService;

    public FuneralPolicyController(FuneralPolicyService funeralPolicyService) {
        this.funeralPolicyService = funeralPolicyService;
    }

    @GetMapping
    @Operation(summary = "List funeral policies (unpaginated — prefer /page)")
    public Flux<FuneralPolicyResponse> findAll() {
        return funeralPolicyService.findAll().map(FuneralPolicyResponse::from);
    }

    @GetMapping("/page")
    @Operation(summary = "List funeral policies (server-side paginated)",
            description = "Rows carry pre-joined scheme + principal-member names.")
    public Mono<PageResponse<FuneralPolicyRow>> page(@RequestParam(required = false) String status,
                                                      @RequestParam(required = false) UUID schemeId,
                                                      @RequestParam(required = false) String q,
                                                      @RequestParam(required = false) String sortKey,
                                                      @RequestParam(required = false) String sortDirection,
                                                      @RequestParam(defaultValue = "0") int page,
                                                      @RequestParam(defaultValue = "50") int size) {
        return funeralPolicyService.searchPaged(new FuneralPolicyFilterParams(
                status, schemeId, q, sortKey, sortDirection, page, size));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get funeral policy by ID")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Funeral policy found"),
            @ApiResponse(responseCode = "404", description = "Funeral policy not found")
    })
    public Mono<FuneralPolicyResponse> findById(@PathVariable UUID id) {
        return funeralPolicyService.findById(id).map(FuneralPolicyResponse::from);
    }

    @GetMapping("/scheme/{schemeId}")
    @Operation(summary = "List funeral policies by scheme")
    public Flux<FuneralPolicyResponse> findBySchemeId(@PathVariable UUID schemeId) {
        return funeralPolicyService.findBySchemeId(schemeId).map(FuneralPolicyResponse::from);
    }

    @GetMapping("/member/{principalMemberId}")
    @Operation(summary = "List funeral policies by principal member")
    public Flux<FuneralPolicyResponse> findByPrincipalMemberId(@PathVariable UUID principalMemberId) {
        return funeralPolicyService.findByPrincipalMemberId(principalMemberId).map(FuneralPolicyResponse::from);
    }

    @GetMapping("/search")
    @Operation(summary = "Search funeral policies by policy number")
    public Flux<FuneralPolicyResponse> search(@RequestParam String q) {
        return funeralPolicyService.search(q).map(FuneralPolicyResponse::from);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Register a new funeral policy")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Funeral policy created"),
            @ApiResponse(responseCode = "400", description = "Validation error")
    })
    public Mono<FuneralPolicyResponse> create(@Valid @RequestBody CreateFuneralPolicyRequest request,
                                               @AuthenticationPrincipal Jwt jwt) {
        return funeralPolicyService.create(request, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(FuneralPolicyResponse::from);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update funeral policy details")
    public Mono<FuneralPolicyResponse> update(@PathVariable UUID id,
                                               @Valid @RequestBody UpdateFuneralPolicyRequest request,
                                               @AuthenticationPrincipal Jwt jwt) {
        return funeralPolicyService.update(id, request, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(FuneralPolicyResponse::from);
    }

    @PostMapping("/{id}/suspend")
    @Operation(summary = "Suspend funeral policy")
    public Mono<FuneralPolicyResponse> suspend(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return funeralPolicyService.suspend(id, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(FuneralPolicyResponse::from);
    }

    @PostMapping("/{id}/terminate")
    @Operation(summary = "Terminate funeral policy")
    public Mono<FuneralPolicyResponse> terminate(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return funeralPolicyService.terminate(id, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(FuneralPolicyResponse::from);
    }

    @PostMapping("/{id}/clear-billing-override")
    @Operation(summary = "Clear the funeral policy's per-policy pricing override",
            description = "Nulls billing_override_amount, reason, and effective_from so billing falls back to scheme defaults.")
    public Mono<FuneralPolicyResponse> clearBillingOverride(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return funeralPolicyService.clearBillingOverride(id, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(FuneralPolicyResponse::from);
    }
}

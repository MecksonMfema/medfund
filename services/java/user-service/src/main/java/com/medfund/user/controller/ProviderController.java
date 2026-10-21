package com.medfund.user.controller;

import com.medfund.shared.audit.AuditActor;
import com.medfund.user.dto.CreateProviderRequest;
import com.medfund.user.dto.ProviderPage;
import com.medfund.user.dto.ProviderResponse;
import com.medfund.user.dto.ProviderTenantResponse;
import com.medfund.user.dto.UpdateProviderNetworkTierRequest;
import com.medfund.user.dto.UpdateProviderRequest;
import com.medfund.user.entity.ProviderInsuranceLine;
import com.medfund.user.service.ProviderMembershipService;
import com.medfund.user.service.ProviderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/providers")
@RequiredArgsConstructor
@Tag(name = "Providers", description = "Provider onboarding and management - platform-wide registry")
@SecurityRequirement(name = "bearer-jwt")
public class ProviderController {

    private final ProviderService providerService;
    private final ProviderMembershipService membershipService;

    @GetMapping
    @Operation(summary = "Search and list providers (paginated)",
        description = "All params are optional. Returns a page object with content, totalCount, totalPages.")
    public Mono<ProviderPage> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String providerType,
            @RequestParam(defaultValue = "1")  int page,
            @RequestParam(defaultValue = "20") int size) {
        return providerService.searchPage(
            (q != null && !q.isBlank()) ? q : null,
            (status != null && !status.isBlank()) ? status : null,
            (providerType != null && !providerType.isBlank()) ? providerType : null,
            page, size);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get provider by ID")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Provider found"),
        @ApiResponse(responseCode = "404", description = "Provider not found")
    })
    public Mono<ProviderResponse> findById(@PathVariable UUID id) {
        return providerService.findById(id).map(ProviderResponse::from);
    }

    @GetMapping("/status/{status}")
    @Operation(summary = "List providers by status")
    public Flux<ProviderResponse> findByStatus(@PathVariable String status) {
        return providerService.findByStatus(status).map(ProviderResponse::from);
    }

    @GetMapping("/specialty/{specialty}")
    @Operation(summary = "List providers by specialty")
    public Flux<ProviderResponse> findBySpecialty(@PathVariable String specialty) {
        return providerService.findBySpecialty(specialty).map(ProviderResponse::from);
    }

    @GetMapping("/search")
    @Operation(summary = "Search providers by name or practice number")
    public Flux<ProviderResponse> search(@RequestParam String q) {
        return providerService.search(q).map(ProviderResponse::from);
    }

    @GetMapping("/registration/{registrationNumber}")
    @Operation(summary = "Find provider by registration number")
    public Mono<ProviderResponse> findByRegistrationNumber(@PathVariable String registrationNumber) {
        return providerService.findByRegistrationNumber(registrationNumber).map(ProviderResponse::from);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Onboard a new provider",
        description = "Creates provider with pending_verification status, syncs to Keycloak, publishes onboarding event")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Provider onboarded"),
        @ApiResponse(responseCode = "400", description = "Validation error")
    })
    public Mono<ProviderResponse> onboard(@Valid @RequestBody CreateProviderRequest request,
                                           @AuthenticationPrincipal Jwt jwt) {
        return providerService.onboard(request, AuditActor.id(jwt), AuditActor.email(jwt))
                              .map(ProviderResponse::from);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update provider details")
    public Mono<ProviderResponse> update(@PathVariable UUID id,
                                          @Valid @RequestBody UpdateProviderRequest request,
                                          @AuthenticationPrincipal Jwt jwt) {
        return providerService.update(id, request, AuditActor.id(jwt), AuditActor.email(jwt))
                              .map(ProviderResponse::from);
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Update provider network tier (Phase 13 per L1 + L17)",
        description = "Narrow PATCH: only the networkTier column is updated. Used by the "
                    + "in-line dropdown on the provider list. Vocab STANDARD | TIER_1 | TIER_2 | TIER_3.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Network tier updated"),
        @ApiResponse(responseCode = "400", description = "Invalid networkTier value"),
        @ApiResponse(responseCode = "404", description = "Provider not found")
    })
    public Mono<ProviderResponse> updateNetworkTier(@PathVariable UUID id,
                                                     @Valid @RequestBody UpdateProviderNetworkTierRequest request,
                                                     @AuthenticationPrincipal Jwt jwt) {
        return providerService.updateNetworkTier(id, request.networkTier(),
                                                  AuditActor.id(jwt), AuditActor.email(jwt))
                              .map(ProviderResponse::from);
    }

    @PostMapping("/{id}/verify")
    @Operation(summary = "Verify and activate provider")
    public Mono<ProviderResponse> verifyAhfoz(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return providerService.verifyAhfoz(id, AuditActor.id(jwt), AuditActor.email(jwt))
                              .map(ProviderResponse::from);
    }

    @PostMapping("/{id}/suspend")
    @Operation(summary = "Suspend provider")
    public Mono<ProviderResponse> suspend(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return providerService.suspend(id, AuditActor.id(jwt), AuditActor.email(jwt))
                              .map(ProviderResponse::from);
    }

    @PostMapping("/{id}/activate")
    @Operation(summary = "Re-activate a suspended provider")
    public Mono<ProviderResponse> activate(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return providerService.activate(id, AuditActor.id(jwt), AuditActor.email(jwt))
                              .map(ProviderResponse::from);
    }


    // ── Tenant membership (public.provider_tenants) ──────────────────────
    //
    // Providers are platform-scoped; the rows below are what makes one
    // visible to a given tenant. claims-service rejects a claim whose
    // provider has no active membership for the submitting tenant, so these
    // are operational endpoints, not bookkeeping.

    @GetMapping("/{id}/tenants")
    @Operation(summary = "List the tenants a provider is contracted with",
        description = "One entry per public.provider_tenants row, including the per-tenant "
                    + "network tier and contract metadata.")
    public Flux<ProviderTenantResponse> listMemberships(@PathVariable UUID id) {
        return membershipService.listMemberships(id).map(ProviderTenantResponse::from);
    }

    @PostMapping("/{id}/tenants/{tenantId}")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Link a provider to a tenant",
        description = "Creates a public.provider_tenants row with default status (active), "
                    + "network tier (STANDARD) and in-network flag, effective today. The "
                    + "contract fields (credit limit, tariff agreement, effective dates) are "
                    + "left null; no editor for them ships in v1.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Membership created"),
        @ApiResponse(responseCode = "404", description = "Provider or tenant not found"),
        @ApiResponse(responseCode = "409", description = "Membership already exists")
    })
    public Mono<ProviderTenantResponse> link(@PathVariable UUID id, @PathVariable UUID tenantId,
                                             @AuthenticationPrincipal Jwt jwt) {
        return membershipService.link(id, tenantId, AuditActor.id(jwt), AuditActor.email(jwt))
                                .map(ProviderTenantResponse::from);
    }

    @DeleteMapping("/{id}/tenants/{tenantId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Unlink a provider from a tenant",
        description = "Idempotent: a membership that is already absent still returns 204.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Membership removed (or already absent)"),
        @ApiResponse(responseCode = "404", description = "Provider or tenant not found")
    })
    public Mono<Void> unlink(@PathVariable UUID id, @PathVariable UUID tenantId,
                             @AuthenticationPrincipal Jwt jwt) {
        return membershipService.unlink(id, tenantId, AuditActor.id(jwt), AuditActor.email(jwt));
    }

    // ── Insurance-line tags (public.provider_insurance_lines) ────────────

    @GetMapping("/{id}/insurance-lines")
    @Operation(summary = "List the insurance lines a provider is tagged for",
        description = "Line codes from the InsuranceLine enum: HEALTH, LIFE, FUNERAL, GROUP, "
                    + "TRAVEL, DISABILITY, VEHICLE, PROPERTY.")
    public Mono<List<String>> listLines(@PathVariable UUID id) {
        // Collected rather than streamed: a Flux<String> is encoded by
        // CharSequenceEncoder as concatenated text/plain, not a JSON array,
        // so the admin console would receive "HEALTHTRAVEL".
        return membershipService.listLines(id)
                                .map(ProviderInsuranceLine::getInsuranceLine)
                                .collectList();
    }

    @PostMapping("/{id}/insurance-lines/{line}")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Tag a provider with an insurance line",
        description = "The line code is normalised through the InsuranceLine enum, so the UI "
                    + "alias MOTOR stores as VEHICLE. A claim whose scheme line is missing "
                    + "from this list is rejected by claims-service with 422.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Tag added"),
        @ApiResponse(responseCode = "400", description = "Unknown insurance line"),
        @ApiResponse(responseCode = "404", description = "Provider not found"),
        @ApiResponse(responseCode = "409", description = "Tag already exists")
    })
    public Mono<Void> addLine(@PathVariable UUID id, @PathVariable String line,
                              @AuthenticationPrincipal Jwt jwt) {
        return membershipService.addLine(id, line, AuditActor.id(jwt), AuditActor.email(jwt)).then();
    }

    @DeleteMapping("/{id}/insurance-lines/{line}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove an insurance-line tag from a provider",
        description = "Idempotent: a tag that is already absent still returns 204.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Tag removed (or already absent)"),
        @ApiResponse(responseCode = "400", description = "Unknown insurance line"),
        @ApiResponse(responseCode = "404", description = "Provider not found")
    })
    public Mono<Void> removeLine(@PathVariable UUID id, @PathVariable String line,
                                 @AuthenticationPrincipal Jwt jwt) {
        return membershipService.removeLine(id, line, AuditActor.id(jwt), AuditActor.email(jwt));
    }

}

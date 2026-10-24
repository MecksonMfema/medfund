package com.medfund.kyc.controller;

import com.medfund.kyc.dto.VerificationResponse;
import com.medfund.kyc.repository.KycVerificationRepository;
import com.medfund.shared.entitlement.ModuleKey;
import com.medfund.shared.security.RequiresEntitlement;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * Read surface for party-neutral KYC verifications (Plan A Phase 2 skeleton).
 *
 * <p>{@code @RequiresEntitlement(KYC)} on the class is the in-service backstop
 * (G4): the gateway's edge {@code EntitlementGate} already denies un-entitled
 * tenants on the {@code /api/v1/verifications} prefix, but a per-tenant paid
 * module cannot rely on a single choke point — the aspect fails closed on every
 * method here if the tenant's KYC entitlement is off (403) or the tenant is
 * unresolved. This is the route the end-to-end gate protects (200 entitled /
 * 403 un-entitled).
 *
 * <p>Live-but-empty by design: the create/verify/reject flow and the subject
 * resolvers land in Plan B. Today this proves the boundary.
 */
@RestController
@RequestMapping("/api/v1/verifications")
@RequiredArgsConstructor
@Tag(name = "Verifications", description = "Party-neutral KYC verifications (gated by the KYC module entitlement)")
@SecurityRequirement(name = "bearer-jwt")
@RequiresEntitlement(ModuleKey.KYC)
public class VerificationController {

    private final KycVerificationRepository verificationRepository;

    @GetMapping
    @Operation(
            summary = "List KYC verifications for the current tenant",
            description = "Returns every verification in the caller's tenant schema, newest first. "
                    + "Requires the tenant's KYC module entitlement to be enabled — an un-entitled "
                    + "tenant receives 403.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Verifications listed (possibly empty)"),
            @ApiResponse(responseCode = "403", description = "Tenant is not entitled to the KYC module")
    })
    public Flux<VerificationResponse> list() {
        return verificationRepository.findAllOrdered().map(VerificationResponse::from);
    }
}

package com.medfund.claims.controller;

import com.medfund.claims.dto.EligibilityQuoteRequest;
import com.medfund.claims.dto.EligibilityQuoteResponse;
import com.medfund.claims.service.EligibilityQuoteService;
import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.security.Permissions;
import com.medfund.shared.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Point-of-service eligibility quote (Phase 3, G9). Providers hit this
 * endpoint before submitting a claim to see what the member will owe.
 * Read-only — no {@code claims} row is written.
 */
@RestController
@RequestMapping("/api/v1/eligibility-quote")
@Tag(name = "Eligibility Quote",
        description = "Pre-service cost-share quote — POS eligibility inquiry.")
@SecurityRequirement(name = "bearer-jwt")
@RequiredArgsConstructor
public class EligibilityQuoteController {

    private final EligibilityQuoteService service;

    @PostMapping
    @RequiresPermission(Permissions.CLAIMS_REQUEST_QUOTE)
    @Operation(summary = "Quote member cost-share for a proposed service before submitting a claim",
            description = "Runs a read-only adjudication against a transient claim built from the "
                    + "request and returns the seven cost-share buckets (allowed, copay, coinsurance, "
                    + "shortfall, patient responsibility, plan-paid, OOP-max remaining). No claim "
                    + "row is created. Emits a medfund.claims.quote-issued audit event.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Quote issued"),
            @ApiResponse(responseCode = "400", description = "Validation error"),
            @ApiResponse(responseCode = "404", description = "Member policy number not found"),
            @ApiResponse(responseCode = "403", description = "Caller lacks claims:request_quote")
    })
    public Mono<EligibilityQuoteResponse> quote(@Valid @RequestBody EligibilityQuoteRequest request,
                                                 @AuthenticationPrincipal Jwt jwt) {
        UUID providerId = providerIdFromJwt(jwt);
        return service.quote(request, providerId, AuditActor.id(jwt), AuditActor.email(jwt));
    }

    /**
     * Derive the provider identity from the JWT claim {@code provider_id}
     * (G9 — never from the request body). Returns null when the caller isn't
     * a provider principal — the quote still runs, the audit event records
     * the missing providerId.
     */
    private static UUID providerIdFromJwt(Jwt jwt) {
        if (jwt == null) return null;
        String raw = jwt.getClaimAsString("provider_id");
        if (raw == null || raw.isBlank()) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

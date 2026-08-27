package com.medfund.claims.controller;

import com.medfund.claims.dto.ClaimReserveHistoryResponse;
import com.medfund.claims.dto.SetClaimReserveRequest;
import com.medfund.claims.service.ClaimReserveHistoryService;
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
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Case-reserve endpoint for the incurred-triangle feed (Phase 14 §A
 * actuarial IBNR). {@code POST /reserve} appends a fresh history row;
 * {@code GET /reserve/history} returns the row list newest-first for
 * the claim-detail "Reserve history" tab.
 *
 * <p>Reserve-setting is gated by {@link Permissions#CLAIMS_SET_RESERVE}
 * rather than {@link Permissions#CLAIMS_ADJUDICATE} so tenants can grant
 * the right to a supervisor role only.
 */
@RestController
@RequestMapping("/api/v1/claims/{claimId}/reserve")
@Tag(name = "Claim Reserve",
        description = "Point-in-time case reserves for the incurred triangle.")
@SecurityRequirement(name = "bearer-jwt")
@RequiredArgsConstructor
public class ClaimReserveController {

    private final ClaimReserveHistoryService service;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permissions.CLAIMS_SET_RESERVE)
    @Operation(summary = "Set or update the case reserve for a claim",
            description = "Appends a fresh row to claim_reserve_history capturing the current "
                    + "case-reserve estimate. Rows are append-only in intent — the incurred "
                    + "triangle reconstructs the reserve as-of a past date by picking the "
                    + "latest row before that date. reasonNote is mandatory (≥5 chars) so the "
                    + "actuary can audit the estimate later.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Reserve row written"),
            @ApiResponse(responseCode = "400", description = "Validation error"),
            @ApiResponse(responseCode = "403", description = "Caller lacks claims:set_reserve"),
            @ApiResponse(responseCode = "404", description = "Claim not found in this tenant")
    })
    public Mono<ClaimReserveHistoryResponse> set(@PathVariable UUID claimId,
                                                 @Valid @RequestBody SetClaimReserveRequest body,
                                                 @AuthenticationPrincipal Jwt jwt) {
        return service.set(claimId, body.reservedAmount(), body.reasonNote(),
                        AuditActor.id(jwt), AuditActor.email(jwt))
                .map(ClaimReserveHistoryResponse::from);
    }

    @GetMapping("/history")
    @RequiresPermission(Permissions.CLAIMS_VIEW)
    @Operation(summary = "List reserve history for a claim (newest first)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Rows returned"),
            @ApiResponse(responseCode = "403", description = "Caller lacks claims:view")
    })
    public Flux<ClaimReserveHistoryResponse> history(@PathVariable UUID claimId) {
        return service.history(claimId).map(ClaimReserveHistoryResponse::from);
    }
}

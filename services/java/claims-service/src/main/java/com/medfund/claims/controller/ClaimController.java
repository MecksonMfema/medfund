package com.medfund.claims.controller;

import com.medfund.claims.dto.ClaimLineResponse;
import com.medfund.claims.dto.ClaimResponse;
import com.medfund.claims.dto.ClaimSubmissionResponse;
import com.medfund.claims.dto.LineDecisionRequest;
import com.medfund.claims.dto.SubmitClaimRequest;
import com.medfund.claims.repository.ClaimLineRepository;
import com.medfund.claims.service.ClaimService;
import com.medfund.shared.audit.AuditActor;
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
@RequestMapping("/api/v1/claims")
@Tag(name = "Claims", description = "Claim submission, verification, adjudication, and lifecycle management")
@SecurityRequirement(name = "bearer-jwt")
public class ClaimController {

    private final ClaimService claimService;
    private final ClaimLineRepository claimLineRepository;

    public ClaimController(ClaimService claimService, ClaimLineRepository claimLineRepository) {
        this.claimService = claimService;
        this.claimLineRepository = claimLineRepository;
    }

    @GetMapping
    @Operation(summary = "List all claims")
    public Flux<ClaimResponse> findAll() {
        return claimService.findAll().map(ClaimResponse::from);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get claim by ID")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Claim found"),
        @ApiResponse(responseCode = "404", description = "Claim not found")
    })
    public Mono<ClaimResponse> findById(@PathVariable UUID id) {
        return claimService.findById(id).map(ClaimResponse::from);
    }

    @GetMapping("/number/{claimNumber}")
    @Operation(summary = "Get claim by claim number")
    public Mono<ClaimResponse> findByClaimNumber(@PathVariable String claimNumber) {
        return claimService.findByClaimNumber(claimNumber).map(ClaimResponse::from);
    }

    @GetMapping("/member/{memberId}")
    @Operation(summary = "List claims by member")
    public Flux<ClaimResponse> findByMemberId(@PathVariable UUID memberId) {
        return claimService.findByMemberId(memberId).map(ClaimResponse::from);
    }

    @GetMapping("/provider/{providerId}")
    @Operation(summary = "List claims by provider")
    public Flux<ClaimResponse> findByProviderId(@PathVariable UUID providerId) {
        return claimService.findByProviderId(providerId).map(ClaimResponse::from);
    }

    @GetMapping("/status/{status}")
    @Operation(summary = "List claims by status")
    public Flux<ClaimResponse> findByStatus(@PathVariable String status) {
        return claimService.findByStatus(status).map(ClaimResponse::from);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Submit a new claim",
        description = "Creates claim with insurance-line-aware validation and verification code, "
                    + "saves claim lines, publishes submission event, and returns the operator-"
                    + "facing capture metadata (code, 5-minute window, batch number) inline.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Claim submitted"),
        @ApiResponse(responseCode = "400", description = "Validation error")
    })
    public Mono<ClaimSubmissionResponse> submit(@Valid @RequestBody SubmitClaimRequest request,
                                                 @AuthenticationPrincipal Jwt jwt) {
        return claimService.submit(request, AuditActor.id(jwt), AuditActor.email(jwt));
    }

    // NOTE: the /verify endpoint was removed on 2026-07-11. Operator-
    // captured claims are now marked VERIFIED at submit time — see
    // ClaimService.submit. When the provider-portal ships and providers
    // start capturing their own claims, verification comes back for
    // *those* claims only (the operator flow stays as-is).

    @PostMapping("/{id}/adjudicate")
    @Operation(summary = "Run 6-stage adjudication pipeline",
        description = "Eligibility → Waiting periods → Benefit limits → Pre-auth → Tariff/pricing → Clinical validation")
    public Mono<ClaimResponse> adjudicate(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return claimService.adjudicate(id, AuditActor.id(jwt), AuditActor.email(jwt)).map(ClaimResponse::from);
    }

    @PostMapping("/{id}/status")
    @Operation(summary = "Update claim status (commit, pay)")
    public Mono<ClaimResponse> updateStatus(@PathVariable UUID id,
                                             @RequestParam String status,
                                             @AuthenticationPrincipal Jwt jwt) {
        return claimService.updateStatus(id, status, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(ClaimResponse::from);
    }

    @GetMapping("/{claimId}/lines")
    @Operation(summary = "Get claim lines for a claim")
    public Flux<ClaimLineResponse> getClaimLines(@PathVariable UUID claimId) {
        return claimLineRepository.findByClaimId(claimId).map(ClaimLineResponse::from);
    }

    @PostMapping("/{id}/lines/decisions")
    @Operation(summary = "Apply per-line adjudicator decisions",
        description = "Accept or reject individual lines with per-line approved amounts. "
                    + "The claim's aggregate approvedAmount is recomputed from the accepted "
                    + "lines' totals; the claim status moves to ADJUDICATED (any accepted) "
                    + "or REJECTED (all rejected).")
    public Mono<ClaimResponse> applyLineDecisions(@PathVariable("id") UUID claimId,
                                                    @RequestBody java.util.List<LineDecisionRequest> decisions,
                                                    @AuthenticationPrincipal Jwt jwt) {
        return claimService.applyLineDecisions(claimId, decisions,
                        AuditActor.id(jwt), AuditActor.email(jwt))
                .map(ClaimResponse::from);
    }
}

package com.medfund.user.controller;

import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.security.RequiresPermission;
import com.medfund.user.dto.CreatePolicyUnitLedgerRequest;
import com.medfund.user.dto.PolicyUnitLedgerResponse;
import com.medfund.user.service.PolicyUnitLedgerService;
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

import java.util.UUID;

/**
 * Phase 15 §8 (I4) — append-only per-policy unit ledger. Called by the
 * policy issuance flow on initial VFA policy issuance and on subsequent
 * fund switches.
 */
@RestController
@RequestMapping("/api/v1/underwriting")
@Tag(name = "Underwriting — Policy Unit Ledger",
     description = "Append-only unit transactions per policy on unit-linked funds")
@SecurityRequirement(name = "bearer-jwt")
@RequiredArgsConstructor
public class PolicyUnitLedgerController {

    private final PolicyUnitLedgerService service;

    @GetMapping("/funds/{fundId}/ledger")
    @Operation(summary = "List ledger rows for a fund",
               description = "Read-only view — used by tenant admin for policy allocations across "
                             + "the fund. Descending by transaction_date.")
    public Flux<PolicyUnitLedgerResponse> findByFundId(@PathVariable UUID fundId) {
        return service.findByFundId(fundId).map(PolicyUnitLedgerResponse::from);
    }

    @GetMapping("/policies/{policyId}/ledger")
    @Operation(summary = "List ledger rows for a policy",
               description = "Descending by transaction_date.")
    public Flux<PolicyUnitLedgerResponse> findByPolicyId(@PathVariable UUID policyId) {
        return service.findByPolicyId(policyId).map(PolicyUnitLedgerResponse::from);
    }

    @PostMapping("/funds/{fundId}/ledger")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission("underwriting.fund:manage")
    @Operation(summary = "Append a ledger row on a fund",
               description = "Called by the policy issuance flow (initial purchase) or by the "
                             + "fund-switch flow (FUND_SWITCH_IN / FUND_SWITCH_OUT).")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Row appended"),
        @ApiResponse(responseCode = "404", description = "Fund not found"),
        @ApiResponse(responseCode = "403", description = "Missing underwriting.fund:manage")
    })
    public Mono<PolicyUnitLedgerResponse> append(
            @PathVariable UUID fundId,
            @Valid @RequestBody CreatePolicyUnitLedgerRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return service.append(fundId, request, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(PolicyUnitLedgerResponse::from);
    }
}

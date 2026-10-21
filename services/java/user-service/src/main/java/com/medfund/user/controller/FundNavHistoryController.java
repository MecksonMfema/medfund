package com.medfund.user.controller;

import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.security.RequiresPermission;
import com.medfund.user.dto.CreateFundNavHistoryRequest;
import com.medfund.user.dto.FundNavHistoryResponse;
import com.medfund.user.service.FundNavHistoryService;
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
 * Phase 15 §8 (I4) — NAV history for a fund. Read-only after insert;
 * corrections go via a new valuation_date row.
 */
@RestController
@RequestMapping("/api/v1/underwriting/funds/{fundId}/nav-history")
@Tag(name = "Underwriting - Fund NAV History",
     description = "Daily NAV series per unit-linked fund")
@SecurityRequirement(name = "bearer-jwt")
@RequiredArgsConstructor
public class FundNavHistoryController {

    private final FundNavHistoryService service;

    @GetMapping
    @Operation(summary = "List NAV rows for a fund, most recent first")
    public Flux<FundNavHistoryResponse> findByFundId(@PathVariable UUID fundId) {
        return service.findByFundId(fundId).map(FundNavHistoryResponse::from);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission("underwriting.fund:manage")
    @Operation(summary = "Append a NAV row for a fund",
               description = "Rejected with 400 if valuation_date is in the future or 409 on "
                             + "duplicate (fund_id, valuation_date).")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "NAV row created"),
        @ApiResponse(responseCode = "400", description = "valuation_date in the future"),
        @ApiResponse(responseCode = "404", description = "Fund not found"),
        @ApiResponse(responseCode = "409", description = "Duplicate (fund, valuation_date)"),
        @ApiResponse(responseCode = "403", description = "Missing underwriting.fund:manage")
    })
    public Mono<FundNavHistoryResponse> create(
            @PathVariable UUID fundId,
            @Valid @RequestBody CreateFundNavHistoryRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return service.create(fundId, request, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(FundNavHistoryResponse::from);
    }
}

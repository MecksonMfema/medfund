package com.medfund.user.controller;

import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.security.RequiresPermission;
import com.medfund.user.dto.CreateIfrs17OpeningBalanceSeedRequest;
import com.medfund.user.dto.Ifrs17OpeningBalanceSeedResponse;
import com.medfund.user.dto.UpdateIfrs17OpeningBalanceSeedRequest;
import com.medfund.user.service.Ifrs17OpeningBalanceSeedService;
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
 * Phase 15 §7 (I29) — CRUD for tenant-admin overrides on auto-derived
 * IFRS 17 opening balances. §17 shaping consults the seed table first
 * for each (portfolio, cohort, currency, balance_type) tuple; falls back
 * to the auto-derived value when no seed row exists.
 */
@RestController
@RequestMapping("/api/v1/underwriting/opening-balances")
@Tag(name = "Underwriting — IFRS 17 Opening Balance Seeds",
     description = "Tenant-admin overrides on auto-derived IFRS 17 LRC / LIC opening balances")
@SecurityRequirement(name = "bearer-jwt")
@RequiredArgsConstructor
public class Ifrs17OpeningBalanceSeedController {

    private final Ifrs17OpeningBalanceSeedService service;

    @GetMapping
    @Operation(summary = "List IFRS 17 opening balance seeds",
               description = "Returns every seed row for the tenant, ordered by "
                             + "(portfolio, cohort, currency, balance_type, effective_from DESC).")
    public Flux<Ifrs17OpeningBalanceSeedResponse> findAll() {
        return service.findAll().map(Ifrs17OpeningBalanceSeedResponse::from);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get an opening balance seed by ID")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Seed found"),
        @ApiResponse(responseCode = "404", description = "Seed not found")
    })
    public Mono<Ifrs17OpeningBalanceSeedResponse> findById(@PathVariable UUID id) {
        return service.findById(id).map(Ifrs17OpeningBalanceSeedResponse::from);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission("underwriting.opening_balance:manage")
    @Operation(summary = "Create an IFRS 17 opening balance seed")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Seed created"),
        @ApiResponse(responseCode = "400", description = "Cohort does not belong to portfolio"),
        @ApiResponse(responseCode = "403", description = "Missing underwriting.opening_balance:manage"),
        @ApiResponse(responseCode = "404", description = "Portfolio or cohort not found"),
        @ApiResponse(responseCode = "409", description = "Duplicate (portfolio, cohort, currency, balance_type, effective_from)")
    })
    public Mono<Ifrs17OpeningBalanceSeedResponse> create(
            @Valid @RequestBody CreateIfrs17OpeningBalanceSeedRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return service.create(request, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(Ifrs17OpeningBalanceSeedResponse::from);
    }

    @PutMapping("/{id}")
    @RequiresPermission("underwriting.opening_balance:manage")
    @Operation(summary = "Update an IFRS 17 opening balance seed",
               description = "Only amount + reasonNote are editable. To retarget a different "
                             + "(portfolio, cohort, currency, balance_type, effective_from) tuple, "
                             + "delete + re-add.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Seed updated"),
        @ApiResponse(responseCode = "404", description = "Seed not found"),
        @ApiResponse(responseCode = "403", description = "Missing underwriting.opening_balance:manage")
    })
    public Mono<Ifrs17OpeningBalanceSeedResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateIfrs17OpeningBalanceSeedRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return service.update(id, request, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(Ifrs17OpeningBalanceSeedResponse::from);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission("underwriting.opening_balance:manage")
    @Operation(summary = "Delete an IFRS 17 opening balance seed",
               description = "Hard delete — reverts the affected tuple back to auto-derived on the "
                             + "next report run. Emits an audit DELETE event capturing the removed row.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Seed deleted"),
        @ApiResponse(responseCode = "404", description = "Seed not found"),
        @ApiResponse(responseCode = "403", description = "Missing underwriting.opening_balance:manage")
    })
    public Mono<Void> delete(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return service.delete(id, AuditActor.id(jwt), AuditActor.email(jwt));
    }
}

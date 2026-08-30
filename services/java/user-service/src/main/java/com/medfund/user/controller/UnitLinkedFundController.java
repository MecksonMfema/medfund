package com.medfund.user.controller;

import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.security.RequiresPermission;
import com.medfund.user.dto.CreateUnitLinkedFundRequest;
import com.medfund.user.dto.UnitLinkedFundResponse;
import com.medfund.user.dto.UpdateUnitLinkedFundRequest;
import com.medfund.user.service.UnitLinkedFundService;
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
 * Phase 15 §8 (I4) — CRUD for VFA unit-linked funds. §16 VFA compute
 * reads active funds + their NAV history + variable fee schedule for
 * direct-participation contracts.
 */
@RestController
@RequestMapping("/api/v1/underwriting/funds")
@Tag(name = "Underwriting — VFA Unit-Linked Funds",
     description = "Catalog of unit-linked funds used by VFA (Variable Fee Approach) measurement")
@SecurityRequirement(name = "bearer-jwt")
@RequiredArgsConstructor
public class UnitLinkedFundController {

    private final UnitLinkedFundService service;

    @GetMapping
    @Operation(summary = "List unit-linked funds",
               description = "Returns every fund for the tenant, active first, then by name.")
    public Flux<UnitLinkedFundResponse> findAll() {
        return service.findAll().map(UnitLinkedFundResponse::from);
    }

    @GetMapping("/active")
    @Operation(summary = "List active unit-linked funds",
               description = "Convenience list for new-policy fund pickers.")
    public Flux<UnitLinkedFundResponse> findActive() {
        return service.findActive().map(UnitLinkedFundResponse::from);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a unit-linked fund by ID")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Fund found"),
        @ApiResponse(responseCode = "404", description = "Fund not found")
    })
    public Mono<UnitLinkedFundResponse> findById(@PathVariable UUID id) {
        return service.findById(id).map(UnitLinkedFundResponse::from);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission("underwriting.fund:manage")
    @Operation(summary = "Create a unit-linked fund")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Fund created"),
        @ApiResponse(responseCode = "409", description = "A fund with this name already exists"),
        @ApiResponse(responseCode = "403", description = "Missing underwriting.fund:manage")
    })
    public Mono<UnitLinkedFundResponse> create(
            @Valid @RequestBody CreateUnitLinkedFundRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return service.create(request, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(UnitLinkedFundResponse::from);
    }

    @PutMapping("/{id}")
    @RequiresPermission("underwriting.fund:manage")
    @Operation(summary = "Update a unit-linked fund",
               description = "Currency is immutable — to change it, delete + re-add.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Fund updated"),
        @ApiResponse(responseCode = "404", description = "Fund not found"),
        @ApiResponse(responseCode = "403", description = "Missing underwriting.fund:manage")
    })
    public Mono<UnitLinkedFundResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateUnitLinkedFundRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return service.update(id, request, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(UnitLinkedFundResponse::from);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission("underwriting.fund:manage")
    @Operation(summary = "Delete a unit-linked fund",
               description = "Hard delete — rejected with 409 if NAV history, ledger rows or fee "
                             + "schedules reference the fund. Prefer deactivating instead.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Fund deleted"),
        @ApiResponse(responseCode = "404", description = "Fund not found"),
        @ApiResponse(responseCode = "409", description = "Fund still referenced by history or ledger rows"),
        @ApiResponse(responseCode = "403", description = "Missing underwriting.fund:manage")
    })
    public Mono<Void> delete(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return service.delete(id, AuditActor.id(jwt), AuditActor.email(jwt));
    }
}

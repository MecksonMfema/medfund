package com.medfund.user.controller;

import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.security.RequiresPermission;
import com.medfund.user.dto.CreateVariableFeeScheduleRequest;
import com.medfund.user.dto.UpdateVariableFeeScheduleRequest;
import com.medfund.user.dto.VariableFeeScheduleResponse;
import com.medfund.user.service.VariableFeeScheduleService;
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
 * Phase 15 §8 (I4) — CRUD for effective-date-versioned variable fee % per
 * fund. §16 VFA compute picks the effective row per reporting period.
 */
@RestController
@RequestMapping("/api/v1/underwriting")
@Tag(name = "Underwriting - VFA Variable Fee Schedule",
     description = "Effective-date-versioned fee % per unit-linked fund")
@SecurityRequirement(name = "bearer-jwt")
@RequiredArgsConstructor
public class VariableFeeScheduleController {

    private final VariableFeeScheduleService service;

    @GetMapping("/funds/{fundId}/variable-fees")
    @Operation(summary = "List variable fee rows for a fund",
               description = "Descending by effective_from.")
    public Flux<VariableFeeScheduleResponse> findByFundId(@PathVariable UUID fundId) {
        return service.findByFundId(fundId).map(VariableFeeScheduleResponse::from);
    }

    @PostMapping("/funds/{fundId}/variable-fees")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission("underwriting.fund:manage")
    @Operation(summary = "Create a variable fee schedule row",
               description = "Rejected with 409 on duplicate (fund_id, effective_from) or 400 when "
                             + "effective_to <= effective_from.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Row created"),
        @ApiResponse(responseCode = "400", description = "Invalid effective window"),
        @ApiResponse(responseCode = "404", description = "Fund not found"),
        @ApiResponse(responseCode = "409", description = "Duplicate (fund_id, effective_from)"),
        @ApiResponse(responseCode = "403", description = "Missing underwriting.fund:manage")
    })
    public Mono<VariableFeeScheduleResponse> create(
            @PathVariable UUID fundId,
            @Valid @RequestBody CreateVariableFeeScheduleRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return service.create(fundId, request, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(VariableFeeScheduleResponse::from);
    }

    @PutMapping("/variable-fees/{id}")
    @RequiresPermission("underwriting.fund:manage")
    @Operation(summary = "Update a variable fee schedule row",
               description = "Only effective_to and fee_percentage are editable - retargeting "
                             + "effective_from means delete + re-add.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Row updated"),
        @ApiResponse(responseCode = "400", description = "Invalid effective window"),
        @ApiResponse(responseCode = "404", description = "Row not found"),
        @ApiResponse(responseCode = "403", description = "Missing underwriting.fund:manage")
    })
    public Mono<VariableFeeScheduleResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateVariableFeeScheduleRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return service.update(id, request, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(VariableFeeScheduleResponse::from);
    }

    @DeleteMapping("/variable-fees/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission("underwriting.fund:manage")
    @Operation(summary = "Delete a variable fee schedule row")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Row deleted"),
        @ApiResponse(responseCode = "404", description = "Row not found"),
        @ApiResponse(responseCode = "403", description = "Missing underwriting.fund:manage")
    })
    public Mono<Void> delete(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return service.delete(id, AuditActor.id(jwt), AuditActor.email(jwt));
    }
}

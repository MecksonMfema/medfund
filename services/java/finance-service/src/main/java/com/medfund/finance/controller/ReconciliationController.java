package com.medfund.finance.controller;

import com.medfund.finance.dto.BankReconciliationResponse;
import com.medfund.finance.dto.CreateReconciliationRequest;
import com.medfund.finance.service.ReconciliationService;
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
@RequestMapping("/api/v1/reconciliations")
@Tag(name = "Bank Reconciliation", description = "Bank statement reconciliation — matching and status tracking")
@SecurityRequirement(name = "bearer-jwt")
public class ReconciliationController {

    private final ReconciliationService reconciliationService;

    public ReconciliationController(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @GetMapping
    @Operation(summary = "List all reconciliation records")
    public Flux<BankReconciliationResponse> findAll() {
        return reconciliationService.findAll().map(BankReconciliationResponse::from);
    }

    @GetMapping("/status/{status}")
    @Operation(summary = "List reconciliation records by status")
    public Flux<BankReconciliationResponse> findByStatus(@PathVariable String status) {
        return reconciliationService.findByStatus(status).map(BankReconciliationResponse::from);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a reconciliation record",
        description = "Computes difference between statement and system amounts, auto-assigns matched/unmatched status")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Reconciliation record created"),
        @ApiResponse(responseCode = "400", description = "Validation error")
    })
    public Mono<BankReconciliationResponse> create(@Valid @RequestBody CreateReconciliationRequest request,
                                                    @AuthenticationPrincipal Jwt jwt) {
        return reconciliationService.create(request, AuditActor.id(jwt), AuditActor.email(jwt)).map(BankReconciliationResponse::from);
    }

    @PostMapping("/{id}/match")
    @Operation(summary = "Manually mark a reconciliation as matched")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Reconciliation marked as matched"),
        @ApiResponse(responseCode = "404", description = "Reconciliation not found")
    })
    public Mono<BankReconciliationResponse> markMatched(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return reconciliationService.markMatched(id, AuditActor.id(jwt), AuditActor.email(jwt)).map(BankReconciliationResponse::from);
    }

    @PostMapping("/{id}/investigate")
    @Operation(summary = "Flag a reconciliation as under investigation")
    public Mono<BankReconciliationResponse> markInvestigating(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return reconciliationService.markInvestigating(id, AuditActor.id(jwt), AuditActor.email(jwt)).map(BankReconciliationResponse::from);
    }

    @PostMapping("/{id}/resolve")
    @Operation(summary = "Mark a previously-investigating reconciliation as resolved")
    public Mono<BankReconciliationResponse> markResolved(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return reconciliationService.markResolved(id, AuditActor.id(jwt), AuditActor.email(jwt)).map(BankReconciliationResponse::from);
    }
}

package com.medfund.contributions.controller;

import com.medfund.contributions.dto.BadDebtResponse;
import com.medfund.contributions.service.BadDebtService;
import com.medfund.shared.audit.AuditActor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/bad-debts")
@Tag(name = "Bad Debts", description = "Bad debt flagging, write-off, and recovery tracking")
@SecurityRequirement(name = "bearer-jwt")
public class BadDebtController {

    private final BadDebtService badDebtService;

    public BadDebtController(BadDebtService badDebtService) {
        this.badDebtService = badDebtService;
    }

    @GetMapping
    @Operation(summary = "List all bad debts")
    public Flux<BadDebtResponse> findAll() {
        return badDebtService.findAll().map(BadDebtResponse::from);
    }

    @GetMapping("/member/{memberId}")
    @Operation(summary = "List bad debts for a member")
    public Flux<BadDebtResponse> findByMemberId(@PathVariable UUID memberId) {
        return badDebtService.findByMemberId(memberId).map(BadDebtResponse::from);
    }

    @GetMapping("/status/{status}")
    @Operation(summary = "List bad debts by status",
        description = "Valid statuses: FLAGGED, WRITTEN_OFF, RECOVERED")
    public Flux<BadDebtResponse> findByStatus(@PathVariable String status) {
        return badDebtService.findByStatus(status).map(BadDebtResponse::from);
    }

    @PostMapping("/{id}/write-off")
    @Operation(summary = "Write off a bad debt")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Bad debt written off"),
        @ApiResponse(responseCode = "404", description = "Bad debt not found"),
        @ApiResponse(responseCode = "400", description = "Bad debt is not in FLAGGED status")
    })
    public Mono<BadDebtResponse> writeOff(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return badDebtService.writeOff(id, AuditActor.id(jwt), AuditActor.email(jwt)).map(BadDebtResponse::from);
    }

    @PostMapping("/{id}/recovered")
    @Operation(summary = "Mark a bad debt as recovered")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Bad debt marked as recovered"),
        @ApiResponse(responseCode = "404", description = "Bad debt not found")
    })
    public Mono<BadDebtResponse> markRecovered(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return badDebtService.markRecovered(id, AuditActor.id(jwt), AuditActor.email(jwt)).map(BadDebtResponse::from);
    }
}

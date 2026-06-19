package com.medfund.contributions.controller;

import com.medfund.contributions.dto.SchemeChangeRequest;
import com.medfund.contributions.dto.SchemeChangeResponse;
import com.medfund.contributions.service.SchemeChangeService;
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
@RequestMapping("/api/v1/scheme-changes")
@Tag(name = "Scheme Changes", description = "Scheme change request, review, and approval workflow")
@SecurityRequirement(name = "bearer-jwt")
public class SchemeChangeController {

    private final SchemeChangeService schemeChangeService;

    public SchemeChangeController(SchemeChangeService schemeChangeService) {
        this.schemeChangeService = schemeChangeService;
    }

    @GetMapping
    @Operation(summary = "List all pending scheme change requests")
    public Flux<SchemeChangeResponse> findPending() {
        return schemeChangeService.findPending().map(SchemeChangeResponse::from);
    }

    @GetMapping("/member/{memberId}")
    @Operation(summary = "List scheme changes for a member")
    public Flux<SchemeChangeResponse> findByMemberId(@PathVariable UUID memberId) {
        return schemeChangeService.findByMemberId(memberId).map(SchemeChangeResponse::from);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Request a scheme change",
        description = "Creates a scheme change request in PENDING status awaiting approval")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Scheme change requested"),
        @ApiResponse(responseCode = "400", description = "Validation error")
    })
    public Mono<SchemeChangeResponse> request(@Valid @RequestBody SchemeChangeRequest request,
                                              @AuthenticationPrincipal Jwt jwt) {
        return schemeChangeService.request(request, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(SchemeChangeResponse::from);
    }

    @PostMapping("/{id}/approve")
    @Operation(summary = "Approve a scheme change request")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Scheme change approved"),
        @ApiResponse(responseCode = "404", description = "Scheme change not found"),
        @ApiResponse(responseCode = "400", description = "Scheme change is not in PENDING status")
    })
    public Mono<SchemeChangeResponse> approve(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return schemeChangeService.approve(id, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(SchemeChangeResponse::from);
    }

    @PostMapping("/{id}/reject")
    @Operation(summary = "Reject a scheme change request")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Scheme change rejected"),
        @ApiResponse(responseCode = "404", description = "Scheme change not found"),
        @ApiResponse(responseCode = "400", description = "Scheme change is not in PENDING status")
    })
    public Mono<SchemeChangeResponse> reject(@PathVariable UUID id,
                                              @RequestParam String reason,
                                              @AuthenticationPrincipal Jwt jwt) {
        return schemeChangeService.reject(id, reason, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(SchemeChangeResponse::from);
    }
}

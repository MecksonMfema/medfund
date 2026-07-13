package com.medfund.claims.controller;

import com.medfund.claims.dto.PageResponse;
import com.medfund.claims.dto.PreAuthRequest;
import com.medfund.claims.dto.PreAuthResponse;
import com.medfund.claims.dto.PreAuthorizationFilterParams;
import com.medfund.claims.dto.PreAuthorizationRow;
import com.medfund.claims.service.PreAuthService;
import com.medfund.shared.audit.AuditActor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.medfund.shared.validation.EndOfMonth;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/pre-authorizations")
@Tag(name = "Pre-Authorizations", description = "Pre-authorization request and approval workflow")
@SecurityRequirement(name = "bearer-jwt")
@Validated
public class PreAuthController {

    private final PreAuthService preAuthService;

    public PreAuthController(PreAuthService preAuthService) {
        this.preAuthService = preAuthService;
    }

    @GetMapping
    @Operation(summary = "List pre-authorizations by status (unpaginated — prefer /page)")
    public Flux<PreAuthResponse> findByStatus(@RequestParam(defaultValue = "PENDING") String status) {
        return preAuthService.findByStatus(status).map(PreAuthResponse::from);
    }

    @GetMapping("/page")
    @Operation(summary = "Server-side paginated, sortable, filterable pre-auths list",
        description = "Feeds /tenant/claims/preauth. Member + provider names joined "
                + "into every row. Sortable keys: authNumber, memberName, providerName, "
                + "tariffCode, status, requestedAmount, approvedAmount, requestedDate, "
                + "expiryDate, createdAt.")
    @ApiResponse(responseCode = "200", description = "Page of pre-authorizations")
    public Mono<PageResponse<PreAuthorizationRow>> searchPaged(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID memberId,
            @RequestParam(required = false) UUID providerId,
            @RequestParam(required = false) UUID schemeId,
            @RequestParam(required = false) String tariffCode,
            @RequestParam(required = false) String q,
            @RequestParam(required = false, defaultValue = "createdAt") String sortKey,
            @RequestParam(required = false, defaultValue = "desc") String sortDirection,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "50") int size) {
        var params = new PreAuthorizationFilterParams(
                status, memberId, providerId, schemeId, tariffCode, q,
                sortKey, sortDirection, page, size);
        return preAuthService.searchPaged(params);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get pre-authorization by ID")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Pre-authorization found"),
        @ApiResponse(responseCode = "404", description = "Pre-authorization not found")
    })
    public Mono<PreAuthResponse> findById(@PathVariable UUID id) {
        return preAuthService.findById(id).map(PreAuthResponse::from);
    }

    @GetMapping("/member/{memberId}")
    @Operation(summary = "List pre-authorizations by member")
    public Flux<PreAuthResponse> findByMemberId(@PathVariable UUID memberId) {
        return preAuthService.findByMemberId(memberId).map(PreAuthResponse::from);
    }

    @GetMapping("/number/{authNumber}")
    @Operation(summary = "Get pre-authorization by auth number")
    public Mono<PreAuthResponse> findByAuthNumber(@PathVariable String authNumber) {
        return preAuthService.findByAuthNumber(authNumber).map(PreAuthResponse::from);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Request pre-authorization")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Pre-authorization requested"),
        @ApiResponse(responseCode = "400", description = "Validation error")
    })
    public Mono<PreAuthResponse> request(@Valid @RequestBody PreAuthRequest request,
                                          @AuthenticationPrincipal Jwt jwt) {
        return preAuthService.request(request, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(PreAuthResponse::from);
    }

    @PostMapping("/{id}/approve")
    @Operation(summary = "Approve pre-authorization")
    public Mono<PreAuthResponse> approve(@PathVariable UUID id,
                                          @RequestParam BigDecimal approvedAmount,
                                          @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) @EndOfMonth LocalDate expiryDate,
                                          @AuthenticationPrincipal Jwt jwt) {
        return preAuthService.approve(id, approvedAmount, expiryDate, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(PreAuthResponse::from);
    }

    @PostMapping("/{id}/reject")
    @Operation(summary = "Reject pre-authorization")
    public Mono<PreAuthResponse> reject(@PathVariable UUID id,
                                         @RequestParam String reason,
                                         @AuthenticationPrincipal Jwt jwt) {
        return preAuthService.reject(id, reason, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(PreAuthResponse::from);
    }
}

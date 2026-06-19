package com.medfund.claims.controller;

import com.medfund.claims.dto.QuotationRequest;
import com.medfund.claims.dto.QuotationResponse;
import com.medfund.claims.service.QuotationService;
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

import java.math.BigDecimal;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/quotations")
@Tag(name = "Quotations", description = "Pre-treatment cost estimate and coverage quotation workflow")
@SecurityRequirement(name = "bearer-jwt")
public class QuotationController {

    private final QuotationService quotationService;

    public QuotationController(QuotationService quotationService) {
        this.quotationService = quotationService;
    }

    @GetMapping
    @Operation(summary = "List pending quotations")
    public Flux<QuotationResponse> findAll() {
        return quotationService.findAll().map(QuotationResponse::from);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get quotation by ID")
    public Mono<QuotationResponse> findById(@PathVariable UUID id) {
        return quotationService.findById(id).map(QuotationResponse::from);
    }

    @GetMapping("/member/{memberId}")
    @Operation(summary = "List quotations for a member")
    public Flux<QuotationResponse> findByMemberId(@PathVariable UUID memberId) {
        return quotationService.findByMemberId(memberId).map(QuotationResponse::from);
    }

    @GetMapping("/provider/{providerId}")
    @Operation(summary = "List quotations from a provider")
    public Flux<QuotationResponse> findByProviderId(@PathVariable UUID providerId) {
        return quotationService.findByProviderId(providerId).map(QuotationResponse::from);
    }

    @GetMapping("/status/{status}")
    @Operation(summary = "List quotations by status")
    public Flux<QuotationResponse> findByStatus(@PathVariable String status) {
        return quotationService.findByStatus(status).map(QuotationResponse::from);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Submit a cost quotation request",
        description = "Provider submits a pre-treatment cost estimate for member review and coverage assessment")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Quotation submitted"),
        @ApiResponse(responseCode = "400", description = "Validation error")
    })
    public Mono<QuotationResponse> submit(@Valid @RequestBody QuotationRequest request,
                                           @AuthenticationPrincipal Jwt jwt) {
        return quotationService.submit(request, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(QuotationResponse::from);
    }

    @PostMapping("/{id}/review")
    @Operation(summary = "Review quotation with coverage estimate",
        description = "Medical aid reviews the quotation and provides covered amount and co-payment")
    public Mono<QuotationResponse> review(
            @PathVariable UUID id,
            @RequestParam BigDecimal coveredAmount,
            @RequestParam BigDecimal coPaymentAmount,
            @RequestParam(required = false) String notes,
            @AuthenticationPrincipal Jwt jwt) {
        return quotationService.review(id, coveredAmount, coPaymentAmount, notes,
                AuditActor.id(jwt), AuditActor.email(jwt))
            .map(QuotationResponse::from);
    }

    @PostMapping("/{id}/approve")
    @Operation(summary = "Approve a reviewed quotation")
    public Mono<QuotationResponse> approve(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return quotationService.approve(id, AuditActor.id(jwt)).map(QuotationResponse::from);
    }

    @PostMapping("/{id}/reject")
    @Operation(summary = "Reject a quotation")
    public Mono<QuotationResponse> reject(@PathVariable UUID id,
                                           @RequestParam String reason,
                                           @AuthenticationPrincipal Jwt jwt) {
        return quotationService.reject(id, reason, AuditActor.id(jwt)).map(QuotationResponse::from);
    }
}

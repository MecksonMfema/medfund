package com.medfund.finance.controller;

import com.medfund.finance.dto.AdvancePaymentApplicationResponse;
import com.medfund.finance.dto.AdvancePaymentDtos.AdvancePaymentResponse;
import com.medfund.finance.dto.AdvancePaymentDtos.CreateAdvancePaymentRequest;
import com.medfund.finance.dto.AdvancePaymentDtos.ReverseAdvancePaymentRequest;
import com.medfund.finance.dto.AdvancePaymentFilterParams;
import com.medfund.finance.dto.AdvancePaymentRow;
import com.medfund.finance.dto.PageResponse;
import com.medfund.finance.service.AdvancePaymentService;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import com.medfund.shared.audit.AuditActor;
import io.swagger.v3.oas.annotations.Operation;
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

@RestController
@RequestMapping("/api/v1/advance-payments")
@RequiredArgsConstructor
@Tag(name = "Advance Payments", description = "Provider / member prepayments — full lifecycle (record, approve, reverse, offset).")
@SecurityRequirement(name = "bearer-jwt")
public class AdvancePaymentController {

    private final AdvancePaymentService service;

    @GetMapping
    @Operation(summary = "List advance payments (unpaginated — prefer /page)")
    public Flux<AdvancePaymentResponse> list(@RequestParam(required = false) UUID providerId,
                                              @RequestParam(required = false) UUID memberId) {
        if (providerId != null) return service.findByProvider(providerId).map(AdvancePaymentResponse::from);
        if (memberId != null) return service.findByMember(memberId).map(AdvancePaymentResponse::from);
        return service.findAll().map(AdvancePaymentResponse::from);
    }

    @GetMapping("/page")
    @Operation(summary = "Server-side paginated, sortable, filterable advance-payments list",
        description = "Feeds /tenant/finance/payments/advance. Provider + member names joined.")
    @ApiResponse(responseCode = "200", description = "Page of advance payments")
    public Mono<PageResponse<AdvancePaymentRow>> searchPaged(
            @RequestParam(required = false) UUID providerId,
            @RequestParam(required = false) UUID memberId,
            @RequestParam(required = false) String currencyCode,
            @RequestParam(required = false) String q,
            @RequestParam(required = false, defaultValue = "recordedAt") String sortKey,
            @RequestParam(required = false, defaultValue = "desc") String sortDirection,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "50") int size) {
        var params = new AdvancePaymentFilterParams(
                providerId, memberId, currencyCode, q, sortKey, sortDirection, page, size);
        return service.searchPaged(params);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get an advance payment")
    public Mono<AdvancePaymentResponse> get(@PathVariable UUID id) {
        return service.findById(id).map(AdvancePaymentResponse::from);
    }

    @GetMapping("/{id}/applications")
    @Operation(summary = "List payment-run applications that consumed this advance",
        description = "Empty until the offset flow has written any application rows.")
    public Flux<AdvancePaymentApplicationResponse> listApplications(@PathVariable UUID id) {
        return service.listApplications(id).map(AdvancePaymentApplicationResponse::from);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Record a new advance payment",
        description = "Amounts at or below the tenant approval threshold auto-approve; above it, the advance lands in 'pending' and requires POST /{id}/approve by a different operator.")
    public Mono<AdvancePaymentResponse> create(@Valid @RequestBody CreateAdvancePaymentRequest request,
                                                @AuthenticationPrincipal Jwt jwt) {
        return service.create(request, AuditActor.id(jwt), AuditActor.email(jwt))
            .map(AdvancePaymentResponse::from);
    }

    @PostMapping("/{id}/approve")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Approve a pending advance payment",
        description = "Requires finance:approve_advance_payment. Approver must be different from the recorder.")
    @ApiResponse(responseCode = "200", description = "Approval accepted; status is now 'approved'")
    @ApiResponse(responseCode = "400", description = "Advance is not pending or approver is the recorder")
    @ApiResponse(responseCode = "404", description = "Advance not found")
    public Mono<AdvancePaymentResponse> approve(@PathVariable UUID id,
                                                 @AuthenticationPrincipal Jwt jwt) {
        return service.approve(id, AuditActor.id(jwt), AuditActor.email(jwt))
            .map(AdvancePaymentResponse::from);
    }

    @PostMapping("/{id}/reverse")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Reverse an approved or applied advance payment",
        description = "Requires finance:reverse_advance_payment. Posts a compensating REVERSAL row; original is marked status='reversed' and never mutates further.")
    @ApiResponse(responseCode = "201", description = "Compensating REVERSAL row created")
    @ApiResponse(responseCode = "400", description = "Advance is pending or already reversed")
    @ApiResponse(responseCode = "404", description = "Advance not found")
    public Mono<AdvancePaymentResponse> reverse(@PathVariable UUID id,
                                                 @Valid @RequestBody ReverseAdvancePaymentRequest body,
                                                 @AuthenticationPrincipal Jwt jwt) {
        return service.reverse(id, body, AuditActor.id(jwt), AuditActor.email(jwt))
            .map(AdvancePaymentResponse::from);
    }
}

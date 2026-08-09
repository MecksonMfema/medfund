package com.medfund.finance.controller;

import com.medfund.finance.dto.CtcPaymentDtos.CreateCtcPaymentRequest;
import com.medfund.finance.dto.CtcPaymentDtos.CtcPaymentResponse;
import com.medfund.finance.dto.CtcPaymentDtos.ReverseCtcPaymentRequest;
import com.medfund.finance.dto.CtcPaymentFilterParams;
import com.medfund.finance.dto.CtcPaymentRow;
import com.medfund.finance.dto.PageResponse;
import com.medfund.finance.service.CtcPaymentService;
import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.security.Permissions;
import com.medfund.shared.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
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
@RequestMapping("/api/v1/ctc-payments")
@RequiredArgsConstructor
@Tag(name = "CTC Payments",
     description = "Claims-to-Contributions transfers — the fund offsets a member's own contribution debt with an approved claim payout that would otherwise be paid to the member.")
@SecurityRequirement(name = "bearer-jwt")
public class CtcPaymentController {

    private final CtcPaymentService service;

    @GetMapping
    @RequiresPermission({Permissions.CLAIMS_VIEW_CTC_PAYMENTS, Permissions.FINANCE_MANAGE_CTC_PAYMENTS})
    @Operation(summary = "List CTC payments (unpaginated — prefer /page)")
    public Flux<CtcPaymentResponse> list(@RequestParam(required = false) Boolean committed) {
        return (committed != null ? service.findByCommitted(committed) : service.findAll())
            .map(CtcPaymentResponse::from);
    }

    @GetMapping("/page")
    @RequiresPermission({Permissions.CLAIMS_VIEW_CTC_PAYMENTS, Permissions.FINANCE_MANAGE_CTC_PAYMENTS})
    @Operation(summary = "Server-side paginated, sortable, filterable CTC list",
        description = "Feeds /tenant/claims/ctc/{pending,committed}. Joins member + "
                    + "group names into every row so the beneficiary chip renders inline. "
                    + "Sortable keys: amount, currencyCode, committed, memberName, "
                    + "groupName, createdAt.")
    @ApiResponse(responseCode = "200", description = "Page of CTC payments")
    public Mono<PageResponse<CtcPaymentRow>> searchPaged(
            @RequestParam(required = false) Boolean committed,
            @RequestParam(required = false) String currencyCode,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Boolean systemDrafted,
            @RequestParam(required = false, defaultValue = "createdAt") String sortKey,
            @RequestParam(required = false, defaultValue = "desc") String sortDirection,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "50") int size) {
        var params = new CtcPaymentFilterParams(
                committed, currencyCode, q, systemDrafted,
                sortKey, sortDirection, page, size);
        return service.searchPaged(params);
    }

    @GetMapping("/{id}")
    @RequiresPermission({Permissions.CLAIMS_VIEW_CTC_PAYMENTS, Permissions.FINANCE_MANAGE_CTC_PAYMENTS})
    @Operation(summary = "Get a CTC payment")
    public Mono<CtcPaymentResponse> get(@PathVariable UUID id) {
        return service.findById(id).map(CtcPaymentResponse::from);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permissions.FINANCE_MANAGE_CTC_PAYMENTS)
    @Operation(summary = "Record a new CTC payment")
    public Mono<CtcPaymentResponse> create(@Valid @RequestBody CreateCtcPaymentRequest request,
                                            @AuthenticationPrincipal Jwt jwt) {
        return service.create(request, AuditActor.id(jwt), AuditActor.email(jwt))
            .map(CtcPaymentResponse::from);
    }

    @PostMapping("/{id}/commit")
    @RequiresPermission({Permissions.CLAIMS_COMMIT_CTC_PAYMENT, Permissions.FINANCE_MANAGE_CTC_PAYMENTS})
    @Operation(summary = "Commit a CTC payment",
        description = "Flips the draft to committed, writes a positive member-payable "
                    + "application row, and publishes medfund.finance.ctc.committed so "
                    + "contributions-service posts the matching CTC_OFFSET transaction.")
    public Mono<CtcPaymentResponse> commit(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return service.commit(id, AuditActor.id(jwt), AuditActor.email(jwt))
            .map(CtcPaymentResponse::from);
    }

    @PostMapping("/{id}/reverse")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permissions.FINANCE_REVERSE_CTC_PAYMENT)
    @Operation(summary = "Reverse a committed CTC payment",
        description = "Marks the original CTC status=reversed and writes a compensating "
                    + "type=REVERSAL row that points back via reverses_ctc_id. A negating "
                    + "application row restores the payable's consumed balance, and "
                    + "medfund.finance.ctc.reversed triggers the CTC_OFFSET_REVERSAL "
                    + "transaction that restores the member's contribution ledger.")
    @ApiResponse(responseCode = "201", description = "Compensating REVERSAL row created")
    public Mono<CtcPaymentResponse> reverse(@PathVariable UUID id,
                                             @Valid @RequestBody ReverseCtcPaymentRequest body,
                                             @AuthenticationPrincipal Jwt jwt) {
        return service.reverse(id, body, AuditActor.id(jwt), AuditActor.email(jwt))
            .map(CtcPaymentResponse::from);
    }
}

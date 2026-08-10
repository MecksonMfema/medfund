package com.medfund.finance.controller;

import com.medfund.finance.dto.PageResponse;
import com.medfund.finance.dto.PaymentAdvice;
import com.medfund.finance.dto.PaymentAdvice.PaymentAdviceLineDto;
import com.medfund.finance.dto.PaymentAdviceFilterParams;
import com.medfund.finance.dto.PaymentAdviceRecordResponse;
import com.medfund.finance.dto.PaymentAdviceRowResponse;
import com.medfund.finance.entity.PaymentAdviceLine;
import com.medfund.finance.entity.PaymentAdviceRecord;
import com.medfund.finance.service.PaymentAdviceService;
import com.medfund.shared.security.Permissions;
import com.medfund.shared.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Parameter;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Payment Advice", description = "Per-payee payment-advice ledger.")
@SecurityRequirement(name = "bearer-jwt")
public class PaymentAdviceController {

    private final PaymentAdviceService paymentAdviceService;

    @GetMapping("/payment-advices/page")
    @Operation(summary = "Server-side paginated, sortable, filterable payment-advices list",
        description = "Feeds /tenant/finance/advice. Sortable keys: adviceNumber, payeeType, "
                + "status, netDueAmount, totalAmount, claimCount, currencyCode, issuedAt, "
                + "periodEndAt, createdAt, runNumber. Free-text `q` searches advice number, "
                + "run number, provider name, and member name.")
    @ApiResponse(responseCode = "200", description = "Page of payment advices")
    @RequiresPermission(Permissions.FINANCE_VIEW_PAYMENT_ADVICE)
    public Mono<PageResponse<PaymentAdviceRowResponse>> searchPaged(
            @RequestParam(required = false) UUID paymentRunId,
            @RequestParam(required = false) UUID providerId,
            @RequestParam(required = false) UUID memberId,
            @RequestParam(required = false) String payeeType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String currencyCode,
            @Parameter(description = "Inclusive lower bound on period_end_at (ISO-8601, e.g. 2026-08-01T00:00:00Z)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant periodStart,
            @Parameter(description = "Inclusive upper bound on period_end_at (ISO-8601, e.g. 2026-08-31T23:59:59Z)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant periodEnd,
            @RequestParam(required = false) String q,
            @RequestParam(required = false, defaultValue = "issuedAt") String sortKey,
            @RequestParam(required = false, defaultValue = "desc") String sortDirection,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "50") int size) {
        var params = new PaymentAdviceFilterParams(
                paymentRunId, providerId, memberId, payeeType, status, currencyCode,
                periodStart, periodEnd, q, sortKey, sortDirection, page, size);
        return paymentAdviceService.searchPaged(params);
    }

    @GetMapping("/payment-advices/{id}")
    @Operation(summary = "Fetch a payment advice with its typed ledger lines")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Advice returned"),
        @ApiResponse(responseCode = "404", description = "Advice not found")
    })
    @RequiresPermission(Permissions.FINANCE_VIEW_PAYMENT_ADVICE)
    public Mono<PaymentAdvice> get(@PathVariable UUID id) {
        return paymentAdviceService.findById(id)
            .flatMap(record -> paymentAdviceService.findLines(record.getId())
                .collectList()
                .map(lines -> toDto(record, lines)));
    }

    @GetMapping("/payment-runs/{runId}/advices")
    @Operation(summary = "List advices generated for a payment run")
    @RequiresPermission(Permissions.FINANCE_VIEW_PAYMENT_ADVICE)
    public Flux<PaymentAdviceRecordResponse> listForRun(@PathVariable UUID runId) {
        return paymentAdviceService.findByRun(runId).map(PaymentAdviceRecordResponse::from);
    }

    @PostMapping("/payment-runs/{runId}/advices/regenerate")
    @Operation(summary = "Delete and re-generate every advice for the run",
        description = "Idempotent from the caller's perspective — replaces any existing advices for the run.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Advices regenerated"),
        @ApiResponse(responseCode = "404", description = "Payment run not found")
    })
    @RequiresPermission(Permissions.FINANCE_GENERATE_PAYMENT_ADVICE)
    public Flux<PaymentAdvice> regenerate(@PathVariable UUID runId) {
        return paymentAdviceService.regenerateAdvicesForRun(runId);
    }

    private PaymentAdvice toDto(PaymentAdviceRecord r, List<PaymentAdviceLine> lines) {
        List<PaymentAdviceLineDto> dtoLines = lines.stream()
            .map(l -> new PaymentAdviceLineDto(
                l.getLineType(), l.getReferenceType(), l.getReferenceId(),
                l.getDescription(), l.getDebitAmount(), l.getCreditAmount(),
                l.getCurrencyCode(), l.getPostedAt(), l.getSequence()))
            .toList();
        return new PaymentAdvice(
            r.getAdviceNumber(),
            r.getPaymentRunId(),
            "",  // runNumber left blank — caller can join if needed
            r.getPayeeType(),
            r.getProviderId(), "",
            r.getMemberId(),   "",
            r.getCurrencyCode(),
            r.getPeriodStartAt(),
            r.getPeriodEndAt(),
            r.getIssuedAt() != null ? r.getIssuedAt() : Instant.now(),
            nz(r.getCarriedInAmount()),
            nz(r.getClaimsPaidAmount()),
            nz(r.getCtcAppliedAmount()),
            nz(r.getAdvanceAppliedAmount()),
            nz(r.getTaxWithheldAmount()),
            nz(r.getShortfallAmount()),
            nz(r.getNetDueAmount()),
            dtoLines);
    }

    private BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}

package com.medfund.finance.controller;

import com.medfund.finance.dto.PaymentAdvice;
import com.medfund.finance.dto.PaymentAdvice.PaymentAdviceLineDto;
import com.medfund.finance.dto.PaymentAdviceRecordResponse;
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

    @GetMapping("/payment-advices")
    @Operation(summary = "List previously generated advices",
        description = "Filter by any combination of run, payee, and period bounds. "
                    + "periodStart / periodEnd match against the advice's period_end_at — "
                    + "an advice \"belongs to\" the month its covering run executed in.")
    @RequiresPermission(Permissions.FINANCE_VIEW_PAYMENT_ADVICE)
    public Flux<PaymentAdviceRecordResponse> list(
            @RequestParam(required = false) UUID paymentRunId,
            @RequestParam(required = false) UUID providerId,
            @RequestParam(required = false) UUID memberId,
            @Parameter(description = "Inclusive lower bound on period_end_at (ISO-8601, e.g. 2026-08-01T00:00:00Z)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant periodStart,
            @Parameter(description = "Inclusive upper bound on period_end_at (ISO-8601, e.g. 2026-08-31T23:59:59Z)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant periodEnd) {
        // Fast paths — preserve back-compat with the single-key filters.
        if (paymentRunId != null && providerId == null && memberId == null
                && periodStart == null && periodEnd == null) {
            return paymentAdviceService.findByRun(paymentRunId).map(PaymentAdviceRecordResponse::from);
        }
        if (providerId != null && paymentRunId == null && memberId == null
                && periodStart == null && periodEnd == null) {
            return paymentAdviceService.findByProvider(providerId).map(PaymentAdviceRecordResponse::from);
        }
        if (memberId != null && paymentRunId == null && providerId == null
                && periodStart == null && periodEnd == null) {
            return paymentAdviceService.findByMember(memberId).map(PaymentAdviceRecordResponse::from);
        }
        if (paymentRunId == null && providerId == null && memberId == null
                && periodStart == null && periodEnd == null) {
            return paymentAdviceService.findAll().map(PaymentAdviceRecordResponse::from);
        }
        return paymentAdviceService.findFiltered(paymentRunId, providerId, memberId, periodStart, periodEnd)
                .map(PaymentAdviceRecordResponse::from);
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

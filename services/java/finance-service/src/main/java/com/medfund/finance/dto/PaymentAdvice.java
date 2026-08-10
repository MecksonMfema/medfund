package com.medfund.finance.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Per-payee ledger view of a payment advice. Header fields carry the
 * running totals (opening balance from the prior run, this-period debits
 * and credits, closing net-due); {@code lines} carries the typed
 * ledger rows. Emitted by
 * {@code PaymentAdviceService.generateAdvicesForRun} — one per
 * (run, payee).
 */
public record PaymentAdvice(
        String adviceNumber,
        UUID paymentRunId,
        String runNumber,
        String payeeType,
        UUID providerId,
        String providerName,
        UUID memberId,
        String memberName,
        String currencyCode,
        Instant periodStartAt,
        Instant periodEndAt,
        Instant generatedAt,
        BigDecimal carriedInAmount,
        BigDecimal claimsPaidAmount,
        BigDecimal ctcAppliedAmount,
        BigDecimal advanceAppliedAmount,
        BigDecimal taxWithheldAmount,
        BigDecimal shortfallAmount,
        BigDecimal netDueAmount,
        List<PaymentAdviceLineDto> lines
) {
    public record PaymentAdviceLineDto(
            String lineType,
            String referenceType,
            UUID referenceId,
            String description,
            BigDecimal debitAmount,
            BigDecimal creditAmount,
            String currencyCode,
            Instant postedAt,
            Integer sequence,
            UUID backPeriodRunId
    ) {}
}

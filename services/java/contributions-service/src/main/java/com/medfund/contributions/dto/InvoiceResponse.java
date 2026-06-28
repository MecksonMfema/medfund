package com.medfund.contributions.dto;

import com.medfund.contributions.entity.Invoice;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record InvoiceResponse(
        UUID id,
        String invoiceNumber,
        UUID schemeId,
        UUID groupId,
        UUID memberId,
        BigDecimal totalAmount,
        String currencyCode,
        LocalDate periodStart,
        LocalDate periodEnd,
        String status,
        LocalDate dueDate,
        Instant issuedAt,
        Instant paidAt,
        // Snapshot fields (V035) — the statement page reads these on the
        // header so a refresh shows the correct opening/closing without
        // re-running the ledger projection.
        Instant committedAt,
        BigDecimal openingBalance,
        BigDecimal closingBalance,
        BigDecimal paymentsInWindow,
        BigDecimal adjustmentsInWindow,
        UUID priorInvoiceId,
        Instant createdAt,
        Instant updatedAt,
        UUID createdBy
) {
    public static InvoiceResponse from(Invoice invoice) {
        return new InvoiceResponse(
                invoice.getId(),
                invoice.getInvoiceNumber(),
                invoice.getSchemeId(),
                invoice.getGroupId(),
                invoice.getMemberId(),
                invoice.getTotalAmount(),
                invoice.getCurrencyCode(),
                invoice.getPeriodStart(),
                invoice.getPeriodEnd(),
                invoice.getStatus(),
                invoice.getDueDate(),
                invoice.getIssuedAt(),
                invoice.getPaidAt(),
                invoice.getCommittedAt(),
                invoice.getOpeningBalance(),
                invoice.getClosingBalance(),
                invoice.getPaymentsInWindow(),
                invoice.getAdjustmentsInWindow(),
                invoice.getPriorInvoiceId(),
                invoice.getCreatedAt(),
                invoice.getUpdatedAt(),
                invoice.getCreatedBy()
        );
    }
}

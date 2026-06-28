package com.medfund.contributions.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Row shape for {@code GET /api/v1/invoices} — the paginated per-invoice
 * listing that fronts {@code /tenant/billing/view}. One row per invoice
 * (group or individual). Names are joined server-side per
 * {@code feedback_no_raw_id_inputs} — operator never sees a UUID.
 *
 * <p>{@code pdfReady} reflects the presence of an {@code invoice_pdfs}
 * pointer row; the row's Download button is gated by it.
 */
public record InvoiceListRow(
        UUID id,
        String invoiceNumber,
        String holderType,            // "GROUP" | "INDIVIDUAL"
        String holderName,            // group.name OR "First Last"
        String holderNumber,          // group.registration_number OR member_number
        String schemeNames,           // comma-joined scheme names (display)
        List<String> insuranceLines,  // exploded so the row can render chips
        LocalDate periodStart,
        LocalDate periodEnd,
        BigDecimal totalAmount,
        String currencyCode,
        int contributionCount,
        String status,
        LocalDate dueDate,
        Instant issuedAt,
        Instant paidAt,
        Instant committedAt,
        BigDecimal openingBalance,
        BigDecimal closingBalance,
        boolean pdfReady
) {}

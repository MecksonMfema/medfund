package com.medfund.contributions.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One expected-receipt row feeding the Phase 8 cash-flow forecast —
 * an unpaid invoice (status not paid/void) whose {@code due_date} falls
 * inside the forecast window. Bucketing by ISO week happens in the
 * forecast service (D8-4); this row keeps the raw amounts.
 */
public record InvoiceReceiptRow(
        String currencyCode,
        LocalDate dueDate,
        BigDecimal amount
) {
}

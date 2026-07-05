package com.medfund.contributions.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One ledger row in a member or group statement.
 * <p>
 * Exactly one of {@code debit} / {@code credit} is non-null on every line —
 * the renderer uses that to decide which column to fill. {@code runningBalance}
 * is the cumulative balance immediately AFTER applying this line.
 */
public record StatementLine(
        Instant date,
        /**
         * Row type — drives per-row rendering on the PDF, Excel and
         * Angular sides. One of:
         * <ul>
         *   <li>{@code OPENING_BALANCE} — "Balance brought forward" bookend
         *       row at the top of the period. Debit + credit both null;
         *       runningBalance carries the opening balance.</li>
         *   <li>{@code CONTRIBUTION} — a per-contribution debit row from a
         *       billing commit.</li>
         *   <li>{@code TRANSACTION} — a transaction (payment, refund,
         *       adjustment) with debit XOR credit set.</li>
         *   <li>{@code CONTRIBUTION_PAID} — synthetic credit row emitted
         *       by the legacy mark-paid path.</li>
         *   <li>{@code CLOSING_BALANCE} — "Balance carried forward"
         *       bookend row at the end of the period. Same shape as
         *       {@code OPENING_BALANCE}; the runningBalance carries the
         *       closing figure that will be next period's opening.</li>
         * </ul>
         */
        String type,
        String description,
        String reference,
        BigDecimal debit,
        BigDecimal credit,
        BigDecimal runningBalance,
        UUID sourceId
) {
    public static final String TYPE_OPENING_BALANCE  = "OPENING_BALANCE";
    public static final String TYPE_CLOSING_BALANCE  = "CLOSING_BALANCE";
    public static final String TYPE_CONTRIBUTION     = "CONTRIBUTION";
    public static final String TYPE_TRANSACTION      = "TRANSACTION";
    public static final String TYPE_CONTRIBUTION_PAID = "CONTRIBUTION_PAID";
}

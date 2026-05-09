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
        String type,           // CONTRIBUTION | TRANSACTION | CONTRIBUTION_PAID
        String description,
        String reference,
        BigDecimal debit,
        BigDecimal credit,
        BigDecimal runningBalance,
        UUID sourceId
) {}

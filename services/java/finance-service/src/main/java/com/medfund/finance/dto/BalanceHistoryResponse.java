package com.medfund.finance.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Phase 6 balance-history report payload (freeze-frame per executed
 * payment run). One row per (payment_run, currency) in the payee's
 * snapshot history, newest first; when {@code ?asAtRun=} is given the
 * row list collapses to exactly that run's snapshots (D6-4). Rows are
 * native per-currency (G34) — no conversion.
 *
 * @param payeeId    provider or member id
 * @param payeeName  joined display name ("" when the payee no longer exists)
 * @param rows       snapshot rows, ordered by taken_at DESC
 */
public record BalanceHistoryResponse(
        UUID payeeId,
        String payeeName,
        List<BalanceHistoryRow> rows
) {

    /**
     * Freeze-frame of the payee's balance at one executed run.
     * {@code openingBalance}/{@code closingBalance} are both the live
     * outstanding balance as it stood at {@code executedAt} (D6-1);
     * {@code netDue} is the run's payout for the payee, taken from its
     * payment advice (or the sum of its item amounts when advice
     * generation was swallowed — D6-3).
     */
    public record BalanceHistoryRow(
            UUID runId,
            String runNumber,
            Instant executedAt,
            String currencyCode,
            BigDecimal openingBalance,
            BigDecimal closingBalance,
            BigDecimal totalClaimed,
            BigDecimal totalApproved,
            BigDecimal totalPaid,
            BigDecimal netDue
    ) {}
}

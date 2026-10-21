package com.medfund.claims.dto;

import java.math.BigDecimal;

/**
 * One cell of the CLAIM_STATUS_LIST pipeline-aging matrix (G49): a
 * (status × age-bucket × currency) aggregate over the submission window.
 * Age buckets are fixed per G49 caveat — "0-3", "4-7", "8-14", "15-30",
 * "&gt;30" days since {@code submission_date}; tenant-configurable
 * bucketing is a follow-up.
 *
 * <p>Cells stay native-currency per G25 — the matrix never merges
 * currencies; {@code currencyCode} is always populated (the DTO's nullable
 * contract is honoured by never producing a mixed-currency cell).
 */
public record ClaimStatusMatrixCell(
        String status,
        String ageBucket,
        long claimCount,
        BigDecimal totalClaimed,
        BigDecimal totalApproved,
        BigDecimal totalPaid,
        String currencyCode
) {
}

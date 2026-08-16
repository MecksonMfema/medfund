package com.medfund.claims.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * CLAIM_STATUS_LIST matrix payload (G49). {@code asOf} is the server clock
 * at report time — age buckets are computed relative to it via
 * {@code NOW()} in SQL, so the client renders ages against this instant.
 */
public record ClaimStatusMatrixResponse(
        LocalDate submittedFrom,
        LocalDate submittedTo,
        List<ClaimStatusMatrixCell> cells,
        Instant asOf
) {
}

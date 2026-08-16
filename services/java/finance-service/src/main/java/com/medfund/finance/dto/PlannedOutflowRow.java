package com.medfund.finance.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One planned payout item feeding the Phase 8 cash-flow forecast. Raw
 * item-level rows from draft/approved runs — all ISO-week bucketing
 * happens in contributions-service so both sides of the forecast bucket
 * identically.
 *
 * <p>Deliberately narrow: only what the forecast needs. Items carry
 * their own currency (a run may hold several per Phase 7), and the run's
 * status is echoed so the composer can distinguish a draft obligation
 * from an approved one without a second lookup.
 */
public record PlannedOutflowRow(
        UUID runId,
        String runNumber,
        String currencyCode,
        BigDecimal amount,
        String runStatus,
        String itemStatus,
        Instant createdAt
) {
}

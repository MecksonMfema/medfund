package com.medfund.claims.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Adjudicator's decision on a single line of a claim. A batch of these
 * is applied atomically via
 * {@code POST /api/v1/claims/{id}/lines/decisions}.
 *
 * <p>{@code status}: {@code ACCEPTED} or {@code REJECTED}. ACCEPTED
 * carries {@code approvedAmount} (may be less than the claimed amount
 * for a partial award). REJECTED carries {@code rejectionReason} — a
 * code from the tenant's rejection-reasons catalogue.
 */
public record LineDecisionRequest(
        @NotNull UUID lineId,
        @NotNull String status,
        BigDecimal approvedAmount,
        String rejectionReason,
        /** Optional code override. When non-null and different from the
         *  current line value, the service snapshots the current code
         *  into {@code original_tariff_code} before overwriting. */
        String tariffCode,
        /** Optional modifier override. Same snapshot semantics as
         *  {@link #tariffCode}. */
        String modifierCodes
) {}

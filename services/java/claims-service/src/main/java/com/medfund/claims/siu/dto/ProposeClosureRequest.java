package com.medfund.claims.siu.dto;

import java.math.BigDecimal;

/**
 * Payload for {@code POST /api/v1/siu/cases/{caseId}/propose-closure}.
 * Transitions UNDER_REVIEW → PENDING_APPROVAL and stages the proposed
 * outcome for supervisor sign-off (four-eyes gate per FR6).
 *
 * <ul>
 *   <li>{@code outcome} must be one of
 *     {@code CONFIRMED_FRAUD | REFERRED_LAW_ENFORCEMENT | ACTION_TAKEN}.
 *     Dismissal skips the four-eyes lane — use
 *     {@code /close-dismissed} directly.</li>
 *   <li>{@code savedAmount} + {@code savedCurrency} feed the FR8 report
 *     savings composite; both must be non-null for non-dismissal outcomes.</li>
 * </ul>
 */
public record ProposeClosureRequest(
        String outcome,
        BigDecimal savedAmount,
        String savedCurrency,
        String closureReason
) {
}

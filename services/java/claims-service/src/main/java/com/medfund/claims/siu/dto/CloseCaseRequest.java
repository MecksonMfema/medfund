package com.medfund.claims.siu.dto;

import java.math.BigDecimal;

/**
 * Close-case body used by both terminal transitions.
 *
 * <ul>
 *   <li><b>CLOSED_CONFIRMED_FRAUD</b> requires all three fields —
 *       {@code closureReason} is investigator narrative,
 *       {@code savedAmount} + {@code savedCurrency} feed the FR8 report.</li>
 *   <li><b>CLOSED_DISMISSED_FALSE_POSITIVE</b> requires only
 *       {@code closureReason}; savings fields must be null.</li>
 * </ul>
 */
public record CloseCaseRequest(
        BigDecimal savedAmount,
        String savedCurrency,
        String closureReason
) {
}

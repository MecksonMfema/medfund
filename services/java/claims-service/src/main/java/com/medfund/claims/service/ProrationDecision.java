package com.medfund.claims.service;

import java.math.BigDecimal;

/**
 * Output of {@link ProrationService#resolveEffectiveLimit}. Carried through
 * stage 3 of adjudication so the pipeline can (a) apply the effective limit,
 * (b) suffix the R03 rejection with the strategy for auditability, and
 * (c) capture the human-readable note in the stage details.
 *
 * @param strategy      the strategy actually applied (may differ from tenant config when
 *                      a rule overrode it, when the cross-currency fallback fired, or
 *                      when no scheme-change context existed)
 * @param effectiveLimit the limit against which the claim's already-consumed sum is compared
 * @param totalConsumed  sum of approved claims in the current calendar year across schemes
 * @param note          short human-readable explanation of the decision (for stage details / audit)
 */
public record ProrationDecision(
        String strategy,
        BigDecimal effectiveLimit,
        BigDecimal totalConsumed,
        String note) {
}

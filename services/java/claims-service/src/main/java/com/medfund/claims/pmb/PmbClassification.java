package com.medfund.claims.pmb;

/**
 * Outcome of a PMB (Prescribed Minimum Benefit) classification pass over a
 * single claim. {@code isPmb=false} + {@code conditionCode=null} means the
 * classifier saw no match — in that case the backfill job leaves the row
 * unchanged.
 *
 * <p>Phase 16 §B REG7 — used by {@link PmbBackfillJob} and (Phase 17) by the
 * adjudication pipeline once {@code RuleCategory.PMB_CLASSIFICATION} lands.
 */
public record PmbClassification(boolean isPmb, String conditionCode) {

    public static final PmbClassification NOT_PMB = new PmbClassification(false, null);

    public static PmbClassification pmb(String conditionCode) {
        return new PmbClassification(true, conditionCode);
    }
}

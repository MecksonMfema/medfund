package com.medfund.finance.regulatory.naic;

/**
 * Enumerated cell targets for the NAIC Schedule P template
 * ({@code NAIC_SCHEDULE_P}). One entry per named-range or anchor-locatable
 * cell the shaper needs to write.
 *
 * <p>NAIC Schedule P reports historical loss + loss-expense reserves by
 * accident year across paid, case, and IBNR components — the real filing
 * is a 10-year triangle. The Phase 12 synthetic template compresses this
 * to the three most-recent accident years ({@code AY_MINUS_2},
 * {@code AY_MINUS_1}, {@code AY_CURRENT}) so the compute + template stack
 * ships end-to-end without a domain-heavy triangle authoring job; the
 * real template swap-in in a downstream sub-phase will expand the enum
 * to the full 10-year set with matching named ranges.
 *
 * <p>The naming convention mirrors the named ranges in the bundled
 * synthetic template ({@code services/java/shared/src/main/resources/report-templates/naic/})
 * — {@code NAIC_P_<part>_<field>}. Section groupings:
 *
 * <ul>
 *   <li>{@code META_*} — company identity, NAIC codes, FEIN, state, period, currency</li>
 *   <li>{@code P1_INCURRED_*} — total incurred losses by accident year (paid + case + IBNR + ULAE)</li>
 *   <li>{@code P2_PAID_*} — paid losses by accident year</li>
 *   <li>{@code P3_CASE_*} — case reserves by accident year</li>
 *   <li>{@code P4_IBNR_*} — bulk + IBNR reserves by accident year</li>
 *   <li>{@code P5_EARNED_PREMIUM_*} — earned premium by accident year</li>
 *   <li>{@code P6_LOSS_RATIO_*} — incurred / earned premium by accident year (computed)</li>
 *   <li>{@code TOTAL_*} — sums / overall ratios across the three accident years</li>
 * </ul>
 *
 * <p>{@code AY_CURRENT} is the accident year of the report period end;
 * {@code AY_MINUS_1} is the prior year; {@code AY_MINUS_2} is two years
 * prior. The shaper computes the absolute year from the period.
 */
public enum NaicPField {

    // ── Header / meta ──────────────────────────────────────────────────────────
    META_COMPANY_NAME,
    META_NAIC_CODE,
    META_GROUP_CODE,
    META_FEIN,
    META_STATE,
    META_REPORTING_CURRENCY,
    META_PERIOD_START,
    META_PERIOD_END,

    // ── Part 1: Incurred losses by accident year ──────────────────────────────
    P1_INCURRED_AY_MINUS_2,
    P1_INCURRED_AY_MINUS_1,
    P1_INCURRED_AY_CURRENT,

    // ── Part 2: Paid losses by accident year ──────────────────────────────────
    P2_PAID_AY_MINUS_2,
    P2_PAID_AY_MINUS_1,
    P2_PAID_AY_CURRENT,

    // ── Part 3: Case reserves by accident year ────────────────────────────────
    P3_CASE_AY_MINUS_2,
    P3_CASE_AY_MINUS_1,
    P3_CASE_AY_CURRENT,

    // ── Part 4: IBNR by accident year ─────────────────────────────────────────
    P4_IBNR_AY_MINUS_2,
    P4_IBNR_AY_MINUS_1,
    P4_IBNR_AY_CURRENT,

    // ── Part 5: Earned premium by accident year ───────────────────────────────
    P5_EARNED_PREMIUM_AY_MINUS_2,
    P5_EARNED_PREMIUM_AY_MINUS_1,
    P5_EARNED_PREMIUM_AY_CURRENT,

    // ── Part 6: Loss ratios by accident year (4-dp decimals) ──────────────────
    P6_LOSS_RATIO_AY_MINUS_2,
    P6_LOSS_RATIO_AY_MINUS_1,
    P6_LOSS_RATIO_AY_CURRENT,

    // ── Totals across accident years ──────────────────────────────────────────
    TOTAL_INCURRED,
    TOTAL_PAID,
    TOTAL_CASE_RESERVES,
    TOTAL_IBNR,
    TOTAL_EARNED_PREMIUM,
    OVERALL_LOSS_RATIO
}

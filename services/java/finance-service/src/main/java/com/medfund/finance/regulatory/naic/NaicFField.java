package com.medfund.finance.regulatory.naic;

/**
 * Enumerated cell targets for the NAIC Schedule F template
 * ({@code NAIC_SCHEDULE_F}). One entry per named-range or anchor-locatable
 * cell the shaper needs to write.
 *
 * <p>NAIC Schedule F reports assumed and ceded reinsurance activity by
 * reinsurer credit-quality stratum — the real filing is a per-reinsurer
 * listing that rolls into part totals plus a computed
 * {@code Provision for Reinsurance} statutory deduction. The Phase 13
 * synthetic template compresses this to per-stratum aggregate rows
 * (affiliated, authorized non-affiliated, unauthorized non-affiliated,
 * certified) so the compute + template stack ships end-to-end without a
 * per-reinsurer authoring job; the real template swap-in in a downstream
 * sub-phase will expand the enum to the per-reinsurer detail with
 * matching named ranges.
 *
 * <p>The naming convention mirrors the named ranges in the bundled
 * synthetic template ({@code services/java/shared/src/main/resources/report-templates/naic/})
 * — {@code NAIC_F_<part>_<field>}. Section groupings:
 *
 * <ul>
 *   <li>{@code META_*} — company identity, NAIC codes, FEIN, state, period, currency</li>
 *   <li>{@code P1_ASSUMED_*} — reinsurance assumed from other insurers
 *       (premiums, losses paid, losses unpaid)</li>
 *   <li>{@code P2_CEDED_AFFILIATED_*} — reinsurance ceded to affiliated
 *       reinsurers (in the same holding-company group)</li>
 *   <li>{@code P3_CEDED_AUTHORIZED_*} — reinsurance ceded to non-affiliated
 *       reinsurers licensed / accredited in the state of domicile</li>
 *   <li>{@code P4_CEDED_UNAUTHORIZED_*} — reinsurance ceded to non-affiliated
 *       reinsurers not licensed / accredited (drive the largest provision)</li>
 *   <li>{@code P5_CEDED_CERTIFIED_*} — reinsurance ceded to Certified
 *       Reinsurers (partially-collateralized under the 2011 credit-for-reinsurance
 *       model law)</li>
 *   <li>{@code TOTAL_*} — sums across all ceded strata + computed
 *       {@code PROVISION_FOR_REINSURANCE} statutory deduction + net position</li>
 * </ul>
 *
 * <p>The compute keeps the assumed section as a header-only report of what
 * the ceding company has taken on; the provision + net-position calc walks
 * only the four ceded strata.
 */
public enum NaicFField {

    // ── Header / meta ──────────────────────────────────────────────────────────
    META_COMPANY_NAME,
    META_NAIC_CODE,
    META_GROUP_CODE,
    META_FEIN,
    META_STATE,
    META_REPORTING_CURRENCY,
    META_PERIOD_START,
    META_PERIOD_END,

    // ── Part 1: Assumed reinsurance ───────────────────────────────────────────
    P1_ASSUMED_PREMIUMS,
    P1_ASSUMED_LOSSES_PAID,
    P1_ASSUMED_LOSSES_UNPAID,

    // ── Part 2: Ceded to affiliated reinsurers ────────────────────────────────
    P2_CEDED_AFFILIATED_PREMIUMS,
    P2_CEDED_AFFILIATED_LOSSES_PAID,
    P2_CEDED_AFFILIATED_LOSSES_UNPAID,

    // ── Part 3: Ceded to non-affiliated authorized reinsurers ─────────────────
    P3_CEDED_AUTHORIZED_PREMIUMS,
    P3_CEDED_AUTHORIZED_LOSSES_PAID,
    P3_CEDED_AUTHORIZED_LOSSES_UNPAID,

    // ── Part 4: Ceded to non-affiliated unauthorized reinsurers ───────────────
    P4_CEDED_UNAUTHORIZED_PREMIUMS,
    P4_CEDED_UNAUTHORIZED_LOSSES_PAID,
    P4_CEDED_UNAUTHORIZED_LOSSES_UNPAID,

    // ── Part 5: Ceded to certified reinsurers ────────────────────────────────
    P5_CEDED_CERTIFIED_PREMIUMS,
    P5_CEDED_CERTIFIED_LOSSES_PAID,
    P5_CEDED_CERTIFIED_LOSSES_UNPAID,

    // ── Totals + statutory provision ─────────────────────────────────────────
    TOTAL_CEDED_PREMIUMS,
    TOTAL_CEDED_LOSSES_PAID,
    TOTAL_CEDED_LOSSES_UNPAID,
    TOTAL_REINSURANCE_RECOVERABLE,
    PROVISION_FOR_REINSURANCE,
    NET_REINSURANCE_POSITION
}

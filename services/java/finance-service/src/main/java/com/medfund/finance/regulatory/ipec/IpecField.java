package com.medfund.finance.regulatory.ipec;

/**
 * Enumerated cell targets for the IPEC quarterly return template
 * ({@code IPEC_QUARTERLY_RETURN}). One entry per named-range or
 * anchor-locatable cell the shaper needs to write.
 *
 * <p>The naming convention mirrors the named ranges in the bundled
 * template ({@code services/java/shared/src/main/resources/report-templates/ipec/})
 * — {@code IPEC_Q_<section>_<field>}. Section groupings:
 *
 * <ul>
 *   <li>{@code META_*} — header identity, period, currency</li>
 *   <li>{@code BS_*} — balance sheet (assets, liabilities, capital)</li>
 *   <li>{@code REV_*} — revenue account (GWP, ceded, NEP, NCI)</li>
 *   <li>{@code UPR_*} — unearned premium reserve by insurance line</li>
 *   <li>{@code OSC_*} — outstanding claims reserve by insurance line</li>
 *   <li>{@code IBNR_*} — IBNR reserve by insurance line</li>
 *   <li>{@code REI_*} — reinsurance recoverables</li>
 *   <li>{@code SOL_*} — solvency (capital, required capital, ratio, margin)</li>
 * </ul>
 */
public enum IpecField {

    // ── Header / meta ──────────────────────────────────────────────────────────
    META_TENANT_NAME,
    META_PERIOD_START,
    META_PERIOD_END,
    META_REPORTING_CURRENCY,
    META_LICENCE_NUMBER,

    // ── Balance sheet ─────────────────────────────────────────────────────────
    BS_TOTAL_ASSETS,
    BS_TOTAL_LIABILITIES,
    BS_TOTAL_EQUITY,

    // ── Revenue account (short-term insurance summary) ────────────────────────
    REV_GWP_HEALTH,
    REV_GWP_MOTOR,
    REV_GWP_PROPERTY,
    REV_CEDED_REINSURANCE,
    REV_NET_EARNED_PREMIUM,
    REV_NET_CLAIMS_INCURRED,
    REV_MANAGEMENT_EXPENSES,
    REV_UNDERWRITING_RESULT,

    // ── Unearned premium reserve ──────────────────────────────────────────────
    UPR_HEALTH,
    UPR_MOTOR,
    UPR_PROPERTY,

    // ── Outstanding claims reserve ────────────────────────────────────────────
    OSC_HEALTH,
    OSC_MOTOR,
    OSC_PROPERTY,

    // ── Incurred but not reported ─────────────────────────────────────────────
    IBNR_HEALTH,
    IBNR_MOTOR,
    IBNR_PROPERTY,

    // ── Reinsurance recoverables ──────────────────────────────────────────────
    REI_RECOVERABLES_OUTSTANDING,
    REI_RECOVERABLES_IBNR,

    // ── Solvency ──────────────────────────────────────────────────────────────
    SOL_ADMITTED_CAPITAL,
    SOL_MIN_REQUIRED_CAPITAL,
    SOL_MARGIN,
    SOL_RATIO
}

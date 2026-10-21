package com.medfund.finance.regulatory.cms;

/**
 * Enumerated cell targets for the CMS Annual Statutory Return template
 * ({@code CMS_ASR}). One entry per named-range or anchor-locatable cell
 * the shaper needs to write.
 *
 * <p>The naming convention mirrors the named ranges in the bundled
 * synthetic template ({@code services/java/shared/src/main/resources/report-templates/cms/})
 * — {@code CMS_ASR_<section>_<field>}. Section groupings:
 *
 * <ul>
 *   <li>{@code META_*} — scheme identity, period, currency, registration number</li>
 *   <li>{@code MEM_*} — membership counts (principal + dependants) + pensioner ratio</li>
 *   <li>{@code BS_*} — balance sheet (assets, liabilities, accumulated funds)</li>
 *   <li>{@code INC_*} — income statement (contributions, claims, non-healthcare costs, surplus)</li>
 *   <li>{@code RATIO_*} — CMS cost-containment ratios (claims / admin / broker / managed-care)</li>
 *   <li>{@code SOL_*} — solvency (accumulated funds, required reserves, actual/minimum ratio, margin)</li>
 * </ul>
 *
 * <p>South African Medical Schemes Act 131 of 1998 requires a minimum 25%
 * solvency ratio measured as {@code accumulated_funds / gross_contribution_income};
 * the ratio + reserve targets are configurable via
 * {@code regulatory-defaults/ZA_CMS_MEDICAL_SCHEME/*.yaml} and — from Phase 15 —
 * overridable per-tenant via {@code RuleCategory.REGULATORY_PARAMETER}.
 */
public enum CmsField {

    // ── Header / meta ──────────────────────────────────────────────────────────
    META_SCHEME_NAME,
    META_REGISTRATION_NUMBER,
    META_REPORTING_CURRENCY,
    META_PERIOD_START,
    META_PERIOD_END,

    // ── Membership ────────────────────────────────────────────────────────────
    MEM_PRINCIPAL_MEMBERS,
    MEM_DEPENDANTS,
    MEM_TOTAL_BENEFICIARIES,
    MEM_PENSIONER_RATIO,

    // ── Balance sheet ─────────────────────────────────────────────────────────
    BS_TOTAL_ASSETS,
    BS_TOTAL_LIABILITIES,
    BS_ACCUMULATED_FUNDS,

    // ── Income statement ──────────────────────────────────────────────────────
    INC_GROSS_CONTRIBUTIONS,
    INC_NET_CONTRIBUTIONS,
    INC_RISK_CLAIMS_INCURRED,
    INC_ADMIN_EXPENSES,
    INC_BROKER_FEES,
    INC_MANAGED_CARE_FEES,
    INC_NON_HEALTHCARE_TOTAL,
    INC_NET_SURPLUS,

    // ── Cost ratios (decimals — 0.80 = 80 %) ──────────────────────────────────
    RATIO_CLAIMS,
    RATIO_NON_HEALTHCARE,
    RATIO_ADMIN,
    RATIO_BROKER,
    RATIO_MANAGED_CARE,

    // ── Solvency ──────────────────────────────────────────────────────────────
    SOL_ACCUMULATED_FUNDS,
    SOL_MIN_REQUIRED_RESERVES,
    SOL_ACTUAL_RATIO,
    SOL_MIN_REQUIRED_RATIO,
    SOL_SURPLUS_DEFICIT
}

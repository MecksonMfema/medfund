package com.medfund.finance.regulatory.tax.wht;

/**
 * Enumerated cell targets for the Withholding Tax return
 * ({@code TAX_WITHHELD_RETURN}). One entry per named-range or
 * anchor-locatable cell the shaper needs to write.
 *
 * <p>The naming convention mirrors the named ranges in the bundled
 * synthetic templates
 * ({@code services/java/shared/src/main/resources/report-templates/tax-withheld/})
 * — {@code WHT_<section>_<field>}. Section groupings:
 *
 * <ul>
 *   <li>{@code META_*} — withholding-agent identity, TIN, period, currency</li>
 *   <li>{@code CAT_*} — per-category payments made + WHT deducted (broker
 *       commission, professional fees, dividends / interest, other).
 *       Each category writes its {@code BASE} + {@code WHT}. Rates come
 *       from {@code public.tenant_tax_config} (Phase 19) where
 *       {@code tax_type='WITHHOLDING'} + matching {@code transaction_category}.</li>
 *   <li>{@code TOTAL_*} — grand totals of payments made + WHT payable</li>
 * </ul>
 *
 * <p>The same enum is populated for both the ZW ITF12B and ZA IRP5-shape
 * synthetic templates — the templates ship with matching named ranges so
 * the shaper is country-agnostic; the reporting currency ({@code ZWL} vs
 * {@code ZAR}) is derived from tenant.country_code via
 * {@link com.medfund.shared.report.regulatory.RegulatoryReportCurrency}.
 */
public enum TaxWithheldField {

    // ── Header / meta ──────────────────────────────────────────────────────────
    META_AGENT_NAME,
    META_TAX_IDENTIFICATION_NUMBER,
    META_COUNTRY,
    META_REPORTING_CURRENCY,
    META_PERIOD_START,
    META_PERIOD_END,

    // ── Per-category paid + WHT ───────────────────────────────────────────────
    CAT_COMMISSION_BASE,
    CAT_COMMISSION_WHT,
    CAT_PROFESSIONAL_FEES_BASE,
    CAT_PROFESSIONAL_FEES_WHT,
    CAT_DIVIDENDS_BASE,
    CAT_DIVIDENDS_WHT,
    CAT_OTHER_BASE,
    CAT_OTHER_WHT,

    // ── Totals ────────────────────────────────────────────────────────────────
    TOTAL_PAYMENTS_BASE,
    TOTAL_WITHHOLDING_PAYABLE
}

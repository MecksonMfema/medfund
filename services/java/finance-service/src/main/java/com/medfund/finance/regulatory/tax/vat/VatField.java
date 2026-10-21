package com.medfund.finance.regulatory.tax.vat;

/**
 * Enumerated cell targets for the VAT Return template ({@code VAT_RETURN}).
 * One entry per named-range or anchor-locatable cell the shaper needs to
 * write.
 *
 * <p>The naming convention mirrors the named ranges in the bundled
 * synthetic templates
 * ({@code services/java/shared/src/main/resources/report-templates/vat/})
 * — {@code VAT_<section>_<field>}. Section groupings:
 *
 * <ul>
 *   <li>{@code META_*} — vendor identity, VAT registration, period, currency</li>
 *   <li>{@code OUTPUT_*} — output VAT collected on sales:
 *       standard-rated (admin fees + commissions + other) + zero-rated
 *       (insurance premiums per ZIMRA / SARS exempt treatment) + the
 *       derived output VAT for each</li>
 *   <li>{@code INPUT_*} — input VAT recoverable on purchases
 *       (admin expenses, professional fees paid)</li>
 *   <li>{@code SUMMARY_*} — net VAT payable / (refundable)</li>
 * </ul>
 *
 * <p>The same enum is populated for both the ZW VAT7 and ZA VAT201
 * synthetic templates — the templates ship with matching named ranges so
 * the shaper is country-agnostic; the reporting currency ({@code ZWL} vs
 * {@code ZAR}) is derived from tenant.country_code via
 * {@link com.medfund.shared.report.regulatory.RegulatoryReportCurrency}.
 */
public enum VatField {

    // ── Header / meta ──────────────────────────────────────────────────────────
    META_VENDOR_NAME,
    META_VAT_REGISTRATION_NUMBER,
    META_COUNTRY,
    META_REPORTING_CURRENCY,
    META_PERIOD_START,
    META_PERIOD_END,

    // ── Output side (sales) ───────────────────────────────────────────────────
    /** Insurance premium income — zero-rated / exempt under both ZIMRA + SARS. */
    OUTPUT_PREMIUM_BASE,
    OUTPUT_PREMIUM_VAT,
    /** Scheme / policy administration fees — standard-rated. */
    OUTPUT_ADMIN_FEE_BASE,
    OUTPUT_ADMIN_FEE_VAT,
    /** Broker / producer commission — standard-rated on the commission itself. */
    OUTPUT_COMMISSION_BASE,
    OUTPUT_COMMISSION_VAT,
    /** Sundry sales not covered above (recoveries, penalties, etc.). */
    OUTPUT_OTHER_BASE,
    OUTPUT_OTHER_VAT,
    /** Grand totals for the output side (standard-rated). */
    OUTPUT_STANDARD_RATED_TOTAL_BASE,
    OUTPUT_STANDARD_RATED_TOTAL_VAT,
    /** Zero-rated total (currently only premium — reserved for future zero-rated items). */
    OUTPUT_ZERO_RATED_TOTAL_BASE,

    // ── Input side (purchases) ────────────────────────────────────────────────
    /** Admin expense purchases (rent, IT, utilities). */
    INPUT_ADMIN_EXPENSES_BASE,
    INPUT_ADMIN_EXPENSES_VAT,
    /** Professional-fee purchases (consulting, audit, actuarial). */
    INPUT_PROFESSIONAL_FEES_BASE,
    INPUT_PROFESSIONAL_FEES_VAT,
    /** Sundry purchases not covered above. */
    INPUT_OTHER_BASE,
    INPUT_OTHER_VAT,
    /** Grand totals for the input side. */
    INPUT_TOTAL_BASE,
    INPUT_TOTAL_VAT,

    // ── Summary ───────────────────────────────────────────────────────────────
    /** Output VAT − Input VAT = net payable (positive) or refundable (negative). */
    SUMMARY_NET_VAT_PAYABLE
}

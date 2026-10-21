package com.medfund.finance.regulatory.pmb;

/**
 * Enumerated cell targets for the CMS Prescribed Minimum Benefit (PMB)
 * Spend report template ({@code PMB_SPEND}). One entry per named-range or
 * anchor-locatable cell the shaper needs to write.
 *
 * <p>The naming convention mirrors the named ranges in the bundled
 * synthetic template
 * ({@code services/java/shared/src/main/resources/report-templates/pmb/})
 * — {@code PMB_<section>_<field>}. Section groupings:
 *
 * <ul>
 *   <li>{@code META_*} — scheme identity, period, currency, registration number</li>
 *   <li>{@code MEM_*} — beneficiary counts (context for per-beneficiary ratios)</li>
 *   <li>{@code CAT_*} — per-condition-category totals (paid amount + claim count).
 *       Categories mirror the {@code industry_default_v1} seeding families from
 *       V172 (respiratory, cardiac, metabolic, oncology, mental health, renal).</li>
 *   <li>{@code TOTAL_*} — grand totals (PMB paid + count + non-PMB paid + ratio)</li>
 * </ul>
 *
 * <p>PMB spend is aggregation-only — the shaper feeds
 * {@code SUM(paid_amount) WHERE is_pmb=TRUE GROUP BY category, currency}
 * (converted to ZAR via {@link com.medfund.finance.regulatory.service.RegulatoryFxPolicy})
 * into the cells; there are no configurable statutory parameters.
 */
public enum PmbField {

    // ── Header / meta ──────────────────────────────────────────────────────────
    META_SCHEME_NAME,
    META_REGISTRATION_NUMBER,
    META_REPORTING_CURRENCY,
    META_PERIOD_START,
    META_PERIOD_END,

    // ── Membership context ────────────────────────────────────────────────────
    MEM_TOTAL_BENEFICIARIES,

    // ── Per-category paid amounts ─────────────────────────────────────────────
    CAT_RESPIRATORY_PAID,
    CAT_CARDIAC_PAID,
    CAT_METABOLIC_PAID,
    CAT_ONCOLOGY_PAID,
    CAT_MENTAL_HEALTH_PAID,
    CAT_RENAL_PAID,
    CAT_OTHER_PAID,

    // ── Per-category claim counts ─────────────────────────────────────────────
    CAT_RESPIRATORY_COUNT,
    CAT_CARDIAC_COUNT,
    CAT_METABOLIC_COUNT,
    CAT_ONCOLOGY_COUNT,
    CAT_MENTAL_HEALTH_COUNT,
    CAT_RENAL_COUNT,
    CAT_OTHER_COUNT,

    // ── Grand totals ──────────────────────────────────────────────────────────
    TOTAL_PMB_PAID,
    TOTAL_PMB_COUNT,
    TOTAL_NON_PMB_PAID,
    TOTAL_ALL_CLAIMS_PAID,
    TOTAL_PMB_RATIO,
    TOTAL_PMB_PAID_PER_BENEFICIARY
}

package com.medfund.finance.regulatory.aml;

import com.medfund.shared.report.regulatory.RegulatoryCellMap;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Maps {@link AmlField} entries to their cell locators inside the bundled
 * AML/STR periodic summary synthetic templates. Every field is a named
 * range in the templates so the shaper is country-agnostic — the shaper
 * picks the country-specific template via
 * {@code AmlXlsxService#pickReportKeyFile}.
 *
 * <p>Real ZW FIU goAML / ZA FIC / US FinCEN SAR filing templates land in
 * Phase 26; Phase 25 ships one synthetic ZA template for round-trip
 * verification. Per-field {@link com.medfund.shared.report.regulatory.LabelAnchor}
 * fallbacks slot in when the real workbooks land if the named ranges
 * don't survive the vendor export.
 */
@Component
public class AmlCellMap implements RegulatoryCellMap<AmlField> {

    private static final List<Mapping<AmlField>> MAPPINGS = List.of(
            // Meta
            new NamedRange<>(AmlField.META_REPORTING_ENTITY_NAME,     "AML_META_ENTITY_NAME"),
            new NamedRange<>(AmlField.META_REGULATOR_REFERENCE,       "AML_META_REGULATOR_REF"),
            new NamedRange<>(AmlField.META_COUNTRY,                   "AML_META_COUNTRY"),
            new NamedRange<>(AmlField.META_REPORTING_CURRENCY,        "AML_META_CURRENCY"),
            new NamedRange<>(AmlField.META_PERIOD_START,              "AML_META_PERIOD_START"),
            new NamedRange<>(AmlField.META_PERIOD_END,                "AML_META_PERIOD_END"),

            // Thresholds
            new NamedRange<>(AmlField.THRESHOLD_PREMIUM,              "AML_THRESHOLD_PREMIUM"),
            new NamedRange<>(AmlField.THRESHOLD_CLAIM_PAYOUT,         "AML_THRESHOLD_CLAIM_PAYOUT"),
            new NamedRange<>(AmlField.THRESHOLD_ADVANCE_PAYMENT,      "AML_THRESHOLD_ADVANCE_PAYMENT"),
            new NamedRange<>(AmlField.THRESHOLD_COMMISSION,           "AML_THRESHOLD_COMMISSION"),
            new NamedRange<>(AmlField.THRESHOLD_OTHER,                "AML_THRESHOLD_OTHER"),

            // Above-threshold activity
            new NamedRange<>(AmlField.ACTIVITY_PREMIUM_COUNT,             "AML_ACTIVITY_PREMIUM_COUNT"),
            new NamedRange<>(AmlField.ACTIVITY_PREMIUM_TOTAL,             "AML_ACTIVITY_PREMIUM_TOTAL"),
            new NamedRange<>(AmlField.ACTIVITY_CLAIM_PAYOUT_COUNT,        "AML_ACTIVITY_CLAIM_PAYOUT_COUNT"),
            new NamedRange<>(AmlField.ACTIVITY_CLAIM_PAYOUT_TOTAL,        "AML_ACTIVITY_CLAIM_PAYOUT_TOTAL"),
            new NamedRange<>(AmlField.ACTIVITY_ADVANCE_PAYMENT_COUNT,     "AML_ACTIVITY_ADVANCE_PAYMENT_COUNT"),
            new NamedRange<>(AmlField.ACTIVITY_ADVANCE_PAYMENT_TOTAL,     "AML_ACTIVITY_ADVANCE_PAYMENT_TOTAL"),
            new NamedRange<>(AmlField.ACTIVITY_COMMISSION_COUNT,          "AML_ACTIVITY_COMMISSION_COUNT"),
            new NamedRange<>(AmlField.ACTIVITY_COMMISSION_TOTAL,          "AML_ACTIVITY_COMMISSION_TOTAL"),
            new NamedRange<>(AmlField.ACTIVITY_OTHER_COUNT,               "AML_ACTIVITY_OTHER_COUNT"),
            new NamedRange<>(AmlField.ACTIVITY_OTHER_TOTAL,               "AML_ACTIVITY_OTHER_TOTAL"),
            new NamedRange<>(AmlField.ACTIVITY_ALL_ABOVE_THRESHOLD_COUNT, "AML_ACTIVITY_TOTAL_COUNT"),
            new NamedRange<>(AmlField.ACTIVITY_ALL_ABOVE_THRESHOLD_TOTAL, "AML_ACTIVITY_TOTAL_AMOUNT"),

            // STR filings
            new NamedRange<>(AmlField.STR_RAISED_COUNT,           "AML_STR_RAISED_COUNT"),
            new NamedRange<>(AmlField.STR_REVIEWED_COUNT,         "AML_STR_REVIEWED_COUNT"),
            new NamedRange<>(AmlField.STR_FILED_COUNT,            "AML_STR_FILED_COUNT"),
            new NamedRange<>(AmlField.STR_CLOSED_COUNT,           "AML_STR_CLOSED_COUNT"),
            new NamedRange<>(AmlField.STR_FILED_TOTAL_AMOUNT,     "AML_STR_FILED_TOTAL_AMOUNT"),

            // Summary
            new NamedRange<>(AmlField.SUMMARY_FILED_RATE,         "AML_SUMMARY_FILED_RATE"));

    @Override
    public Class<AmlField> keyClass() {
        return AmlField.class;
    }

    @Override
    public List<Mapping<AmlField>> mappings() {
        return MAPPINGS;
    }
}

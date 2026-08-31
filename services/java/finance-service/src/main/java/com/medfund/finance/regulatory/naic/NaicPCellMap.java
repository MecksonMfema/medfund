package com.medfund.finance.regulatory.naic;

import com.medfund.shared.report.regulatory.LabelAnchor;
import com.medfund.shared.report.regulatory.RegulatoryCellMap;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Maps {@link NaicPField} entries to their cell locators inside the bundled
 * NAIC Schedule P template. Every field is a named range in the SYNTHETIC
 * template (Phase 12, deferred real-template swap-in when NAIC publishes a
 * machine-readable Annual Statement); once the real template lands,
 * per-field {@link LabelAnchor} fallbacks slot into this list without
 * touching callers.
 *
 * <p>Ordering is preserved for deterministic XLSX writes — the
 * {@link com.medfund.shared.report.regulatory.RegulatoryTemplateService#fill fill}
 * loop walks the list in declared order.
 */
@Component
public class NaicPCellMap implements RegulatoryCellMap<NaicPField> {

    private static final List<Mapping<NaicPField>> MAPPINGS = List.of(
            // Meta
            new NamedRange<>(NaicPField.META_COMPANY_NAME,          "NAIC_P_META_COMPANY_NAME"),
            new NamedRange<>(NaicPField.META_NAIC_CODE,             "NAIC_P_META_NAIC_CODE"),
            new NamedRange<>(NaicPField.META_GROUP_CODE,            "NAIC_P_META_GROUP_CODE"),
            new NamedRange<>(NaicPField.META_FEIN,                  "NAIC_P_META_FEIN"),
            new NamedRange<>(NaicPField.META_STATE,                 "NAIC_P_META_STATE"),
            new NamedRange<>(NaicPField.META_REPORTING_CURRENCY,    "NAIC_P_META_CURRENCY"),
            new NamedRange<>(NaicPField.META_PERIOD_START,          "NAIC_P_META_PERIOD_START"),
            new NamedRange<>(NaicPField.META_PERIOD_END,            "NAIC_P_META_PERIOD_END"),

            // Part 1 — Incurred losses by accident year
            new NamedRange<>(NaicPField.P1_INCURRED_AY_MINUS_2,     "NAIC_P_P1_INCURRED_AY_MINUS_2"),
            new NamedRange<>(NaicPField.P1_INCURRED_AY_MINUS_1,     "NAIC_P_P1_INCURRED_AY_MINUS_1"),
            new NamedRange<>(NaicPField.P1_INCURRED_AY_CURRENT,     "NAIC_P_P1_INCURRED_AY_CURRENT"),

            // Part 2 — Paid losses by accident year
            new NamedRange<>(NaicPField.P2_PAID_AY_MINUS_2,         "NAIC_P_P2_PAID_AY_MINUS_2"),
            new NamedRange<>(NaicPField.P2_PAID_AY_MINUS_1,         "NAIC_P_P2_PAID_AY_MINUS_1"),
            new NamedRange<>(NaicPField.P2_PAID_AY_CURRENT,         "NAIC_P_P2_PAID_AY_CURRENT"),

            // Part 3 — Case reserves by accident year
            new NamedRange<>(NaicPField.P3_CASE_AY_MINUS_2,         "NAIC_P_P3_CASE_AY_MINUS_2"),
            new NamedRange<>(NaicPField.P3_CASE_AY_MINUS_1,         "NAIC_P_P3_CASE_AY_MINUS_1"),
            new NamedRange<>(NaicPField.P3_CASE_AY_CURRENT,         "NAIC_P_P3_CASE_AY_CURRENT"),

            // Part 4 — IBNR by accident year
            new NamedRange<>(NaicPField.P4_IBNR_AY_MINUS_2,         "NAIC_P_P4_IBNR_AY_MINUS_2"),
            new NamedRange<>(NaicPField.P4_IBNR_AY_MINUS_1,         "NAIC_P_P4_IBNR_AY_MINUS_1"),
            new NamedRange<>(NaicPField.P4_IBNR_AY_CURRENT,         "NAIC_P_P4_IBNR_AY_CURRENT"),

            // Part 5 — Earned premium by accident year
            new NamedRange<>(NaicPField.P5_EARNED_PREMIUM_AY_MINUS_2, "NAIC_P_P5_EARNED_PREMIUM_AY_MINUS_2"),
            new NamedRange<>(NaicPField.P5_EARNED_PREMIUM_AY_MINUS_1, "NAIC_P_P5_EARNED_PREMIUM_AY_MINUS_1"),
            new NamedRange<>(NaicPField.P5_EARNED_PREMIUM_AY_CURRENT, "NAIC_P_P5_EARNED_PREMIUM_AY_CURRENT"),

            // Part 6 — Loss ratios by accident year
            new NamedRange<>(NaicPField.P6_LOSS_RATIO_AY_MINUS_2,   "NAIC_P_P6_LOSS_RATIO_AY_MINUS_2"),
            new NamedRange<>(NaicPField.P6_LOSS_RATIO_AY_MINUS_1,   "NAIC_P_P6_LOSS_RATIO_AY_MINUS_1"),
            new NamedRange<>(NaicPField.P6_LOSS_RATIO_AY_CURRENT,   "NAIC_P_P6_LOSS_RATIO_AY_CURRENT"),

            // Totals
            new NamedRange<>(NaicPField.TOTAL_INCURRED,             "NAIC_P_TOTAL_INCURRED"),
            new NamedRange<>(NaicPField.TOTAL_PAID,                 "NAIC_P_TOTAL_PAID"),
            new NamedRange<>(NaicPField.TOTAL_CASE_RESERVES,        "NAIC_P_TOTAL_CASE_RESERVES"),
            new NamedRange<>(NaicPField.TOTAL_IBNR,                 "NAIC_P_TOTAL_IBNR"),
            new NamedRange<>(NaicPField.TOTAL_EARNED_PREMIUM,       "NAIC_P_TOTAL_EARNED_PREMIUM"),
            new NamedRange<>(NaicPField.OVERALL_LOSS_RATIO,         "NAIC_P_OVERALL_LOSS_RATIO"));

    @Override
    public Class<NaicPField> keyClass() {
        return NaicPField.class;
    }

    @Override
    public List<Mapping<NaicPField>> mappings() {
        return MAPPINGS;
    }
}

package com.medfund.finance.regulatory.pmb;

import com.medfund.shared.report.regulatory.LabelAnchor;
import com.medfund.shared.report.regulatory.RegulatoryCellMap;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Maps {@link PmbField} entries to their cell locators inside the bundled
 * PMB Spend synthetic template. Every field is a named range in the
 * SYNTHETIC template (Phase 18, deferred real-template swap-in once CMS
 * publishes a canonical PMB reporting workbook); once a real template
 * lands, per-field {@link LabelAnchor} fallbacks slot into this list
 * without touching callers.
 *
 * <p>Ordering is preserved for deterministic XLSX writes — the
 * {@link com.medfund.shared.report.regulatory.RegulatoryTemplateService#fill fill}
 * loop walks the list in declared order.
 */
@Component
public class PmbCellMap implements RegulatoryCellMap<PmbField> {

    private static final List<Mapping<PmbField>> MAPPINGS = List.of(
            // Meta
            new NamedRange<>(PmbField.META_SCHEME_NAME,             "PMB_META_SCHEME_NAME"),
            new NamedRange<>(PmbField.META_REGISTRATION_NUMBER,     "PMB_META_REGISTRATION_NUMBER"),
            new NamedRange<>(PmbField.META_REPORTING_CURRENCY,      "PMB_META_CURRENCY"),
            new NamedRange<>(PmbField.META_PERIOD_START,            "PMB_META_PERIOD_START"),
            new NamedRange<>(PmbField.META_PERIOD_END,              "PMB_META_PERIOD_END"),

            // Membership
            new NamedRange<>(PmbField.MEM_TOTAL_BENEFICIARIES,      "PMB_MEM_TOTAL_BENEFICIARIES"),

            // Per-category paid amounts
            new NamedRange<>(PmbField.CAT_RESPIRATORY_PAID,         "PMB_CAT_RESPIRATORY_PAID"),
            new NamedRange<>(PmbField.CAT_CARDIAC_PAID,             "PMB_CAT_CARDIAC_PAID"),
            new NamedRange<>(PmbField.CAT_METABOLIC_PAID,           "PMB_CAT_METABOLIC_PAID"),
            new NamedRange<>(PmbField.CAT_ONCOLOGY_PAID,            "PMB_CAT_ONCOLOGY_PAID"),
            new NamedRange<>(PmbField.CAT_MENTAL_HEALTH_PAID,       "PMB_CAT_MENTAL_HEALTH_PAID"),
            new NamedRange<>(PmbField.CAT_RENAL_PAID,               "PMB_CAT_RENAL_PAID"),
            new NamedRange<>(PmbField.CAT_OTHER_PAID,               "PMB_CAT_OTHER_PAID"),

            // Per-category claim counts
            new NamedRange<>(PmbField.CAT_RESPIRATORY_COUNT,        "PMB_CAT_RESPIRATORY_COUNT"),
            new NamedRange<>(PmbField.CAT_CARDIAC_COUNT,            "PMB_CAT_CARDIAC_COUNT"),
            new NamedRange<>(PmbField.CAT_METABOLIC_COUNT,          "PMB_CAT_METABOLIC_COUNT"),
            new NamedRange<>(PmbField.CAT_ONCOLOGY_COUNT,           "PMB_CAT_ONCOLOGY_COUNT"),
            new NamedRange<>(PmbField.CAT_MENTAL_HEALTH_COUNT,      "PMB_CAT_MENTAL_HEALTH_COUNT"),
            new NamedRange<>(PmbField.CAT_RENAL_COUNT,              "PMB_CAT_RENAL_COUNT"),
            new NamedRange<>(PmbField.CAT_OTHER_COUNT,              "PMB_CAT_OTHER_COUNT"),

            // Grand totals
            new NamedRange<>(PmbField.TOTAL_PMB_PAID,               "PMB_TOTAL_PMB_PAID"),
            new NamedRange<>(PmbField.TOTAL_PMB_COUNT,              "PMB_TOTAL_PMB_COUNT"),
            new NamedRange<>(PmbField.TOTAL_NON_PMB_PAID,           "PMB_TOTAL_NON_PMB_PAID"),
            new NamedRange<>(PmbField.TOTAL_ALL_CLAIMS_PAID,        "PMB_TOTAL_ALL_CLAIMS_PAID"),
            new NamedRange<>(PmbField.TOTAL_PMB_RATIO,              "PMB_TOTAL_PMB_RATIO"),
            new NamedRange<>(PmbField.TOTAL_PMB_PAID_PER_BENEFICIARY, "PMB_TOTAL_PMB_PAID_PER_BENEFICIARY"));

    @Override
    public Class<PmbField> keyClass() {
        return PmbField.class;
    }

    @Override
    public List<Mapping<PmbField>> mappings() {
        return MAPPINGS;
    }
}

package com.medfund.finance.regulatory.tax.vat;

import com.medfund.shared.report.regulatory.LabelAnchor;
import com.medfund.shared.report.regulatory.RegulatoryCellMap;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Maps {@link VatField} entries to their cell locators inside the bundled
 * VAT Return synthetic templates. Every field is a named range in both
 * the {@code zw-vat-return-v_SYNTHETIC_2026-08-30.xlsx} +
 * {@code za-vat-return-v_SYNTHETIC_2026-08-30.xlsx} templates so the same
 * cell map covers both countries; the shaper picks the country-specific
 * template via {@link VatXlsxService#REPORT_KEY_FILE}.
 *
 * <p>Once the real ZIMRA VAT7 + SARS VAT201 workbooks land, per-field
 * {@link LabelAnchor} fallbacks slot into this list per country without
 * touching callers — if the two countries end up with divergent cell
 * layouts, split into {@code VatZwCellMap} + {@code VatZaCellMap} then.
 *
 * <p>Ordering is preserved for deterministic XLSX writes — the
 * {@link com.medfund.shared.report.regulatory.RegulatoryTemplateService#fill fill}
 * loop walks the list in declared order.
 */
@Component
public class VatCellMap implements RegulatoryCellMap<VatField> {

    private static final List<Mapping<VatField>> MAPPINGS = List.of(
            // Meta
            new NamedRange<>(VatField.META_VENDOR_NAME,                    "VAT_META_VENDOR_NAME"),
            new NamedRange<>(VatField.META_VAT_REGISTRATION_NUMBER,        "VAT_META_VAT_REGISTRATION_NUMBER"),
            new NamedRange<>(VatField.META_COUNTRY,                        "VAT_META_COUNTRY"),
            new NamedRange<>(VatField.META_REPORTING_CURRENCY,             "VAT_META_CURRENCY"),
            new NamedRange<>(VatField.META_PERIOD_START,                   "VAT_META_PERIOD_START"),
            new NamedRange<>(VatField.META_PERIOD_END,                     "VAT_META_PERIOD_END"),

            // Output side
            new NamedRange<>(VatField.OUTPUT_PREMIUM_BASE,                 "VAT_OUTPUT_PREMIUM_BASE"),
            new NamedRange<>(VatField.OUTPUT_PREMIUM_VAT,                  "VAT_OUTPUT_PREMIUM_VAT"),
            new NamedRange<>(VatField.OUTPUT_ADMIN_FEE_BASE,               "VAT_OUTPUT_ADMIN_FEE_BASE"),
            new NamedRange<>(VatField.OUTPUT_ADMIN_FEE_VAT,                "VAT_OUTPUT_ADMIN_FEE_VAT"),
            new NamedRange<>(VatField.OUTPUT_COMMISSION_BASE,              "VAT_OUTPUT_COMMISSION_BASE"),
            new NamedRange<>(VatField.OUTPUT_COMMISSION_VAT,               "VAT_OUTPUT_COMMISSION_VAT"),
            new NamedRange<>(VatField.OUTPUT_OTHER_BASE,                   "VAT_OUTPUT_OTHER_BASE"),
            new NamedRange<>(VatField.OUTPUT_OTHER_VAT,                    "VAT_OUTPUT_OTHER_VAT"),
            new NamedRange<>(VatField.OUTPUT_STANDARD_RATED_TOTAL_BASE,    "VAT_OUTPUT_STANDARD_TOTAL_BASE"),
            new NamedRange<>(VatField.OUTPUT_STANDARD_RATED_TOTAL_VAT,     "VAT_OUTPUT_STANDARD_TOTAL_VAT"),
            new NamedRange<>(VatField.OUTPUT_ZERO_RATED_TOTAL_BASE,        "VAT_OUTPUT_ZERO_RATED_TOTAL_BASE"),

            // Input side
            new NamedRange<>(VatField.INPUT_ADMIN_EXPENSES_BASE,           "VAT_INPUT_ADMIN_EXPENSES_BASE"),
            new NamedRange<>(VatField.INPUT_ADMIN_EXPENSES_VAT,            "VAT_INPUT_ADMIN_EXPENSES_VAT"),
            new NamedRange<>(VatField.INPUT_PROFESSIONAL_FEES_BASE,        "VAT_INPUT_PROFESSIONAL_FEES_BASE"),
            new NamedRange<>(VatField.INPUT_PROFESSIONAL_FEES_VAT,         "VAT_INPUT_PROFESSIONAL_FEES_VAT"),
            new NamedRange<>(VatField.INPUT_OTHER_BASE,                    "VAT_INPUT_OTHER_BASE"),
            new NamedRange<>(VatField.INPUT_OTHER_VAT,                     "VAT_INPUT_OTHER_VAT"),
            new NamedRange<>(VatField.INPUT_TOTAL_BASE,                    "VAT_INPUT_TOTAL_BASE"),
            new NamedRange<>(VatField.INPUT_TOTAL_VAT,                     "VAT_INPUT_TOTAL_VAT"),

            // Summary
            new NamedRange<>(VatField.SUMMARY_NET_VAT_PAYABLE,             "VAT_SUMMARY_NET_PAYABLE"));

    @Override
    public Class<VatField> keyClass() {
        return VatField.class;
    }

    @Override
    public List<Mapping<VatField>> mappings() {
        return MAPPINGS;
    }
}

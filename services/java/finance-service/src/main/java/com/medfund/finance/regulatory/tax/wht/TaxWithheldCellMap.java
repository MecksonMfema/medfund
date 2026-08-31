package com.medfund.finance.regulatory.tax.wht;

import com.medfund.shared.report.regulatory.LabelAnchor;
import com.medfund.shared.report.regulatory.RegulatoryCellMap;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Maps {@link TaxWithheldField} entries to their cell locators inside
 * the bundled Withholding-Tax return synthetic templates. Every field
 * is a named range in both the
 * {@code zw-tax-withheld-v_SYNTHETIC_YYYY-MM-DD.xlsx} +
 * {@code za-tax-withheld-v_SYNTHETIC_YYYY-MM-DD.xlsx} templates so the
 * same cell map covers both countries; the shaper picks the
 * country-specific template via
 * {@link TaxWithheldXlsxService#pickReportKeyFile}.
 *
 * <p>Once the real ZIMRA ITF12B + SARS IRP5-shape workbooks land,
 * per-field {@link LabelAnchor} fallbacks slot into this list per
 * country without touching callers — split into
 * {@code TaxWithheldZwCellMap} / {@code TaxWithheldZaCellMap} then if
 * the cell layouts diverge.
 */
@Component
public class TaxWithheldCellMap implements RegulatoryCellMap<TaxWithheldField> {

    private static final List<Mapping<TaxWithheldField>> MAPPINGS = List.of(
            // Meta
            new NamedRange<>(TaxWithheldField.META_AGENT_NAME,                 "WHT_META_AGENT_NAME"),
            new NamedRange<>(TaxWithheldField.META_TAX_IDENTIFICATION_NUMBER,  "WHT_META_TIN"),
            new NamedRange<>(TaxWithheldField.META_COUNTRY,                    "WHT_META_COUNTRY"),
            new NamedRange<>(TaxWithheldField.META_REPORTING_CURRENCY,         "WHT_META_CURRENCY"),
            new NamedRange<>(TaxWithheldField.META_PERIOD_START,               "WHT_META_PERIOD_START"),
            new NamedRange<>(TaxWithheldField.META_PERIOD_END,                 "WHT_META_PERIOD_END"),

            // Per-category
            new NamedRange<>(TaxWithheldField.CAT_COMMISSION_BASE,             "WHT_CAT_COMMISSION_BASE"),
            new NamedRange<>(TaxWithheldField.CAT_COMMISSION_WHT,              "WHT_CAT_COMMISSION_WHT"),
            new NamedRange<>(TaxWithheldField.CAT_PROFESSIONAL_FEES_BASE,      "WHT_CAT_PROFESSIONAL_FEES_BASE"),
            new NamedRange<>(TaxWithheldField.CAT_PROFESSIONAL_FEES_WHT,       "WHT_CAT_PROFESSIONAL_FEES_WHT"),
            new NamedRange<>(TaxWithheldField.CAT_DIVIDENDS_BASE,              "WHT_CAT_DIVIDENDS_BASE"),
            new NamedRange<>(TaxWithheldField.CAT_DIVIDENDS_WHT,               "WHT_CAT_DIVIDENDS_WHT"),
            new NamedRange<>(TaxWithheldField.CAT_OTHER_BASE,                  "WHT_CAT_OTHER_BASE"),
            new NamedRange<>(TaxWithheldField.CAT_OTHER_WHT,                   "WHT_CAT_OTHER_WHT"),

            // Totals
            new NamedRange<>(TaxWithheldField.TOTAL_PAYMENTS_BASE,             "WHT_TOTAL_PAYMENTS_BASE"),
            new NamedRange<>(TaxWithheldField.TOTAL_WITHHOLDING_PAYABLE,       "WHT_TOTAL_WITHHOLDING_PAYABLE"));

    @Override
    public Class<TaxWithheldField> keyClass() {
        return TaxWithheldField.class;
    }

    @Override
    public List<Mapping<TaxWithheldField>> mappings() {
        return MAPPINGS;
    }
}

package com.medfund.finance.regulatory.aml;

import com.medfund.shared.report.regulatory.RegulatoryCellMap;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Maps {@link AmlStrField} entries to their {@code AMLSTR_*} named ranges in
 * the Phase 26 per-STR filing templates (ZW FIU goAML, ZA FIC, US FinCEN SAR).
 * All three templates expose the same set of named ranges so the shaper is
 * country-agnostic — {@link AmlStrFilingXlsxService#pickReportKeyFile} only
 * picks the base file and the fill logic is identical.
 */
@Component
public class AmlStrFilingCellMap implements RegulatoryCellMap<AmlStrField> {

    private static final List<Mapping<AmlStrField>> MAPPINGS = List.of(
            // Meta
            new NamedRange<>(AmlStrField.META_REPORTING_ENTITY_NAME, "AMLSTR_META_ENTITY_NAME"),
            new NamedRange<>(AmlStrField.META_REGULATOR_REFERENCE,   "AMLSTR_META_REGULATOR_REF"),
            new NamedRange<>(AmlStrField.META_COUNTRY,               "AMLSTR_META_COUNTRY"),
            new NamedRange<>(AmlStrField.META_TEMPLATE_KEY,          "AMLSTR_META_TEMPLATE_KEY"),
            new NamedRange<>(AmlStrField.META_GENERATED_AT,          "AMLSTR_META_GENERATED_AT"),

            // Alert core
            new NamedRange<>(AmlStrField.ALERT_ID,                   "AMLSTR_ALERT_ID"),
            new NamedRange<>(AmlStrField.ALERT_TRANSACTION_REF,      "AMLSTR_ALERT_TXN_REF"),
            new NamedRange<>(AmlStrField.ALERT_TRANSACTION_TYPE,     "AMLSTR_ALERT_TXN_TYPE"),
            new NamedRange<>(AmlStrField.ALERT_AMOUNT,               "AMLSTR_ALERT_AMOUNT"),
            new NamedRange<>(AmlStrField.ALERT_CURRENCY,             "AMLSTR_ALERT_CURRENCY"),
            new NamedRange<>(AmlStrField.ALERT_MEMBER_ID,            "AMLSTR_ALERT_MEMBER_ID"),
            new NamedRange<>(AmlStrField.ALERT_PROVIDER_ID,          "AMLSTR_ALERT_PROVIDER_ID"),
            new NamedRange<>(AmlStrField.ALERT_DESCRIPTION,          "AMLSTR_ALERT_DESCRIPTION"),

            // Workflow trail
            new NamedRange<>(AmlStrField.ALERT_RAISED_AT,            "AMLSTR_ALERT_RAISED_AT"),
            new NamedRange<>(AmlStrField.ALERT_RAISED_BY_EMAIL,      "AMLSTR_ALERT_RAISED_BY"),
            new NamedRange<>(AmlStrField.ALERT_REVIEWED_AT,          "AMLSTR_ALERT_REVIEWED_AT"),
            new NamedRange<>(AmlStrField.ALERT_REVIEWER_EMAIL,       "AMLSTR_ALERT_REVIEWER"),
            new NamedRange<>(AmlStrField.ALERT_REVIEW_NOTE,          "AMLSTR_ALERT_REVIEW_NOTE"),
            new NamedRange<>(AmlStrField.ALERT_FILED_AT,             "AMLSTR_ALERT_FILED_AT"),
            new NamedRange<>(AmlStrField.ALERT_FILED_BY_EMAIL,       "AMLSTR_ALERT_FILED_BY"),
            new NamedRange<>(AmlStrField.ALERT_FILED_REF,            "AMLSTR_ALERT_FILED_REF"));

    @Override
    public Class<AmlStrField> keyClass() {
        return AmlStrField.class;
    }

    @Override
    public List<Mapping<AmlStrField>> mappings() {
        return MAPPINGS;
    }
}

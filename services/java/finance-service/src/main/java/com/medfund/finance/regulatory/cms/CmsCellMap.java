package com.medfund.finance.regulatory.cms;

import com.medfund.shared.report.regulatory.LabelAnchor;
import com.medfund.shared.report.regulatory.RegulatoryCellMap;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Maps {@link CmsField} entries to their cell locators inside the bundled
 * CMS Annual Statutory Return template. Every field is a named range in
 * the SYNTHETIC template (Phase 11, deferred real-template swap-in when
 * CMS publishes a machine-readable ASR); once the real template lands,
 * per-field {@link LabelAnchor} fallbacks slot into this list without
 * touching callers.
 *
 * <p>Ordering is preserved for deterministic XLSX writes — the
 * {@link com.medfund.shared.report.regulatory.RegulatoryTemplateService#fill fill}
 * loop walks the list in declared order.
 */
@Component
public class CmsCellMap implements RegulatoryCellMap<CmsField> {

    private static final List<Mapping<CmsField>> MAPPINGS = List.of(
            // Meta
            new NamedRange<>(CmsField.META_SCHEME_NAME,             "CMS_ASR_META_SCHEME_NAME"),
            new NamedRange<>(CmsField.META_REGISTRATION_NUMBER,     "CMS_ASR_META_REGISTRATION_NUMBER"),
            new NamedRange<>(CmsField.META_REPORTING_CURRENCY,      "CMS_ASR_META_CURRENCY"),
            new NamedRange<>(CmsField.META_PERIOD_START,            "CMS_ASR_META_PERIOD_START"),
            new NamedRange<>(CmsField.META_PERIOD_END,              "CMS_ASR_META_PERIOD_END"),

            // Membership
            new NamedRange<>(CmsField.MEM_PRINCIPAL_MEMBERS,        "CMS_ASR_MEM_PRINCIPAL_MEMBERS"),
            new NamedRange<>(CmsField.MEM_DEPENDANTS,               "CMS_ASR_MEM_DEPENDANTS"),
            new NamedRange<>(CmsField.MEM_TOTAL_BENEFICIARIES,      "CMS_ASR_MEM_TOTAL_BENEFICIARIES"),
            new NamedRange<>(CmsField.MEM_PENSIONER_RATIO,          "CMS_ASR_MEM_PENSIONER_RATIO"),

            // Balance sheet
            new NamedRange<>(CmsField.BS_TOTAL_ASSETS,              "CMS_ASR_BS_TOTAL_ASSETS"),
            new NamedRange<>(CmsField.BS_TOTAL_LIABILITIES,         "CMS_ASR_BS_TOTAL_LIABILITIES"),
            new NamedRange<>(CmsField.BS_ACCUMULATED_FUNDS,         "CMS_ASR_BS_ACCUMULATED_FUNDS"),

            // Income statement
            new NamedRange<>(CmsField.INC_GROSS_CONTRIBUTIONS,      "CMS_ASR_INC_GROSS_CONTRIBUTIONS"),
            new NamedRange<>(CmsField.INC_NET_CONTRIBUTIONS,        "CMS_ASR_INC_NET_CONTRIBUTIONS"),
            new NamedRange<>(CmsField.INC_RISK_CLAIMS_INCURRED,     "CMS_ASR_INC_RISK_CLAIMS_INCURRED"),
            new NamedRange<>(CmsField.INC_ADMIN_EXPENSES,           "CMS_ASR_INC_ADMIN_EXPENSES"),
            new NamedRange<>(CmsField.INC_BROKER_FEES,              "CMS_ASR_INC_BROKER_FEES"),
            new NamedRange<>(CmsField.INC_MANAGED_CARE_FEES,        "CMS_ASR_INC_MANAGED_CARE_FEES"),
            new NamedRange<>(CmsField.INC_NON_HEALTHCARE_TOTAL,     "CMS_ASR_INC_NON_HEALTHCARE_TOTAL"),
            new NamedRange<>(CmsField.INC_NET_SURPLUS,              "CMS_ASR_INC_NET_SURPLUS"),

            // Cost ratios
            new NamedRange<>(CmsField.RATIO_CLAIMS,                 "CMS_ASR_RATIO_CLAIMS"),
            new NamedRange<>(CmsField.RATIO_NON_HEALTHCARE,         "CMS_ASR_RATIO_NON_HEALTHCARE"),
            new NamedRange<>(CmsField.RATIO_ADMIN,                  "CMS_ASR_RATIO_ADMIN"),
            new NamedRange<>(CmsField.RATIO_BROKER,                 "CMS_ASR_RATIO_BROKER"),
            new NamedRange<>(CmsField.RATIO_MANAGED_CARE,           "CMS_ASR_RATIO_MANAGED_CARE"),

            // Solvency
            new NamedRange<>(CmsField.SOL_ACCUMULATED_FUNDS,        "CMS_ASR_SOL_ACCUMULATED_FUNDS"),
            new NamedRange<>(CmsField.SOL_MIN_REQUIRED_RESERVES,    "CMS_ASR_SOL_MIN_REQUIRED_RESERVES"),
            new NamedRange<>(CmsField.SOL_ACTUAL_RATIO,             "CMS_ASR_SOL_ACTUAL_RATIO"),
            new NamedRange<>(CmsField.SOL_MIN_REQUIRED_RATIO,       "CMS_ASR_SOL_MIN_REQUIRED_RATIO"),
            new NamedRange<>(CmsField.SOL_SURPLUS_DEFICIT,          "CMS_ASR_SOL_SURPLUS_DEFICIT"));

    @Override
    public Class<CmsField> keyClass() {
        return CmsField.class;
    }

    @Override
    public List<Mapping<CmsField>> mappings() {
        return MAPPINGS;
    }
}

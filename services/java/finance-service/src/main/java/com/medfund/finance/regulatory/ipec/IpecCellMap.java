package com.medfund.finance.regulatory.ipec;

import com.medfund.shared.report.regulatory.LabelAnchor;
import com.medfund.shared.report.regulatory.RegulatoryCellMap;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Maps {@link IpecField} entries to their cell locators inside the
 * bundled IPEC quarterly return template. Every field is a named range
 * in the SYNTHETIC template (Phase 10, deferred real-template swap-in);
 * once the real IPEC template lands, per-field {@link LabelAnchor}
 * fallbacks slot into this list without touching callers.
 *
 * <p>Ordering is preserved for deterministic XLSX writes — the
 * {@link com.medfund.shared.report.regulatory.RegulatoryTemplateService#fill fill}
 * loop walks the list in declared order.
 */
@Component
public class IpecCellMap implements RegulatoryCellMap<IpecField> {

    private static final List<Mapping<IpecField>> MAPPINGS = List.of(
            // Meta
            new NamedRange<>(IpecField.META_TENANT_NAME,          "IPEC_Q_META_TENANT_NAME"),
            new NamedRange<>(IpecField.META_PERIOD_START,         "IPEC_Q_META_PERIOD_START"),
            new NamedRange<>(IpecField.META_PERIOD_END,           "IPEC_Q_META_PERIOD_END"),
            new NamedRange<>(IpecField.META_REPORTING_CURRENCY,   "IPEC_Q_META_CURRENCY"),
            new NamedRange<>(IpecField.META_LICENCE_NUMBER,       "IPEC_Q_META_LICENCE"),

            // Balance sheet
            new NamedRange<>(IpecField.BS_TOTAL_ASSETS,           "IPEC_Q_BS_TOTAL_ASSETS"),
            new NamedRange<>(IpecField.BS_TOTAL_LIABILITIES,      "IPEC_Q_BS_TOTAL_LIABILITIES"),
            new NamedRange<>(IpecField.BS_TOTAL_EQUITY,           "IPEC_Q_BS_TOTAL_EQUITY"),

            // Revenue account
            new NamedRange<>(IpecField.REV_GWP_HEALTH,            "IPEC_Q_REV_GWP_HEALTH"),
            new NamedRange<>(IpecField.REV_GWP_MOTOR,             "IPEC_Q_REV_GWP_MOTOR"),
            new NamedRange<>(IpecField.REV_GWP_PROPERTY,          "IPEC_Q_REV_GWP_PROPERTY"),
            new NamedRange<>(IpecField.REV_CEDED_REINSURANCE,     "IPEC_Q_REV_CEDED"),
            new NamedRange<>(IpecField.REV_NET_EARNED_PREMIUM,    "IPEC_Q_REV_NEP"),
            new NamedRange<>(IpecField.REV_NET_CLAIMS_INCURRED,   "IPEC_Q_REV_NCI"),
            new NamedRange<>(IpecField.REV_MANAGEMENT_EXPENSES,   "IPEC_Q_REV_MGMT_EXP"),
            new NamedRange<>(IpecField.REV_UNDERWRITING_RESULT,   "IPEC_Q_REV_UW_RESULT"),

            // UPR / OSC / IBNR (by insurance line)
            new NamedRange<>(IpecField.UPR_HEALTH,                "IPEC_Q_UPR_HEALTH"),
            new NamedRange<>(IpecField.UPR_MOTOR,                 "IPEC_Q_UPR_MOTOR"),
            new NamedRange<>(IpecField.UPR_PROPERTY,              "IPEC_Q_UPR_PROPERTY"),
            new NamedRange<>(IpecField.OSC_HEALTH,                "IPEC_Q_OSC_HEALTH"),
            new NamedRange<>(IpecField.OSC_MOTOR,                 "IPEC_Q_OSC_MOTOR"),
            new NamedRange<>(IpecField.OSC_PROPERTY,              "IPEC_Q_OSC_PROPERTY"),
            new NamedRange<>(IpecField.IBNR_HEALTH,               "IPEC_Q_IBNR_HEALTH"),
            new NamedRange<>(IpecField.IBNR_MOTOR,                "IPEC_Q_IBNR_MOTOR"),
            new NamedRange<>(IpecField.IBNR_PROPERTY,             "IPEC_Q_IBNR_PROPERTY"),

            // Reinsurance recoverables
            new NamedRange<>(IpecField.REI_RECOVERABLES_OUTSTANDING, "IPEC_Q_REI_OUTSTANDING"),
            new NamedRange<>(IpecField.REI_RECOVERABLES_IBNR,        "IPEC_Q_REI_IBNR"),

            // Solvency
            new NamedRange<>(IpecField.SOL_ADMITTED_CAPITAL,      "IPEC_Q_SOL_CAPITAL"),
            new NamedRange<>(IpecField.SOL_MIN_REQUIRED_CAPITAL,  "IPEC_Q_SOL_MIN_REQUIRED"),
            new NamedRange<>(IpecField.SOL_MARGIN,                "IPEC_Q_SOL_MARGIN"),
            new NamedRange<>(IpecField.SOL_RATIO,                 "IPEC_Q_SOL_RATIO"));

    @Override
    public Class<IpecField> keyClass() {
        return IpecField.class;
    }

    @Override
    public List<Mapping<IpecField>> mappings() {
        return MAPPINGS;
    }
}

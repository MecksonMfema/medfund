package com.medfund.finance.regulatory.naic;

import com.medfund.shared.report.regulatory.LabelAnchor;
import com.medfund.shared.report.regulatory.RegulatoryCellMap;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Maps {@link NaicFField} entries to their cell locators inside the bundled
 * NAIC Schedule F template. Every field is a named range in the SYNTHETIC
 * template (Phase 13, deferred real-template swap-in when NAIC publishes a
 * machine-readable Annual Statement); once the real per-reinsurer template
 * lands, per-field {@link LabelAnchor} fallbacks slot into this list
 * without touching callers.
 *
 * <p>Ordering is preserved for deterministic XLSX writes — the
 * {@link com.medfund.shared.report.regulatory.RegulatoryTemplateService#fill fill}
 * loop walks the list in declared order.
 */
@Component
public class NaicFCellMap implements RegulatoryCellMap<NaicFField> {

    private static final List<Mapping<NaicFField>> MAPPINGS = List.of(
            // Meta
            new NamedRange<>(NaicFField.META_COMPANY_NAME,       "NAIC_F_META_COMPANY_NAME"),
            new NamedRange<>(NaicFField.META_NAIC_CODE,          "NAIC_F_META_NAIC_CODE"),
            new NamedRange<>(NaicFField.META_GROUP_CODE,         "NAIC_F_META_GROUP_CODE"),
            new NamedRange<>(NaicFField.META_FEIN,               "NAIC_F_META_FEIN"),
            new NamedRange<>(NaicFField.META_STATE,              "NAIC_F_META_STATE"),
            new NamedRange<>(NaicFField.META_REPORTING_CURRENCY, "NAIC_F_META_CURRENCY"),
            new NamedRange<>(NaicFField.META_PERIOD_START,       "NAIC_F_META_PERIOD_START"),
            new NamedRange<>(NaicFField.META_PERIOD_END,         "NAIC_F_META_PERIOD_END"),

            // Part 1 — Assumed reinsurance
            new NamedRange<>(NaicFField.P1_ASSUMED_PREMIUMS,      "NAIC_F_P1_ASSUMED_PREMIUMS"),
            new NamedRange<>(NaicFField.P1_ASSUMED_LOSSES_PAID,   "NAIC_F_P1_ASSUMED_LOSSES_PAID"),
            new NamedRange<>(NaicFField.P1_ASSUMED_LOSSES_UNPAID, "NAIC_F_P1_ASSUMED_LOSSES_UNPAID"),

            // Part 2 — Ceded to affiliated reinsurers
            new NamedRange<>(NaicFField.P2_CEDED_AFFILIATED_PREMIUMS,      "NAIC_F_P2_CEDED_AFFILIATED_PREMIUMS"),
            new NamedRange<>(NaicFField.P2_CEDED_AFFILIATED_LOSSES_PAID,   "NAIC_F_P2_CEDED_AFFILIATED_LOSSES_PAID"),
            new NamedRange<>(NaicFField.P2_CEDED_AFFILIATED_LOSSES_UNPAID, "NAIC_F_P2_CEDED_AFFILIATED_LOSSES_UNPAID"),

            // Part 3 — Ceded to non-affiliated authorized reinsurers
            new NamedRange<>(NaicFField.P3_CEDED_AUTHORIZED_PREMIUMS,      "NAIC_F_P3_CEDED_AUTHORIZED_PREMIUMS"),
            new NamedRange<>(NaicFField.P3_CEDED_AUTHORIZED_LOSSES_PAID,   "NAIC_F_P3_CEDED_AUTHORIZED_LOSSES_PAID"),
            new NamedRange<>(NaicFField.P3_CEDED_AUTHORIZED_LOSSES_UNPAID, "NAIC_F_P3_CEDED_AUTHORIZED_LOSSES_UNPAID"),

            // Part 4 — Ceded to non-affiliated unauthorized reinsurers
            new NamedRange<>(NaicFField.P4_CEDED_UNAUTHORIZED_PREMIUMS,      "NAIC_F_P4_CEDED_UNAUTHORIZED_PREMIUMS"),
            new NamedRange<>(NaicFField.P4_CEDED_UNAUTHORIZED_LOSSES_PAID,   "NAIC_F_P4_CEDED_UNAUTHORIZED_LOSSES_PAID"),
            new NamedRange<>(NaicFField.P4_CEDED_UNAUTHORIZED_LOSSES_UNPAID, "NAIC_F_P4_CEDED_UNAUTHORIZED_LOSSES_UNPAID"),

            // Part 5 — Ceded to certified reinsurers
            new NamedRange<>(NaicFField.P5_CEDED_CERTIFIED_PREMIUMS,      "NAIC_F_P5_CEDED_CERTIFIED_PREMIUMS"),
            new NamedRange<>(NaicFField.P5_CEDED_CERTIFIED_LOSSES_PAID,   "NAIC_F_P5_CEDED_CERTIFIED_LOSSES_PAID"),
            new NamedRange<>(NaicFField.P5_CEDED_CERTIFIED_LOSSES_UNPAID, "NAIC_F_P5_CEDED_CERTIFIED_LOSSES_UNPAID"),

            // Totals + statutory provision + net position
            new NamedRange<>(NaicFField.TOTAL_CEDED_PREMIUMS,          "NAIC_F_TOTAL_CEDED_PREMIUMS"),
            new NamedRange<>(NaicFField.TOTAL_CEDED_LOSSES_PAID,       "NAIC_F_TOTAL_CEDED_LOSSES_PAID"),
            new NamedRange<>(NaicFField.TOTAL_CEDED_LOSSES_UNPAID,     "NAIC_F_TOTAL_CEDED_LOSSES_UNPAID"),
            new NamedRange<>(NaicFField.TOTAL_REINSURANCE_RECOVERABLE, "NAIC_F_TOTAL_REINSURANCE_RECOVERABLE"),
            new NamedRange<>(NaicFField.PROVISION_FOR_REINSURANCE,     "NAIC_F_PROVISION_FOR_REINSURANCE"),
            new NamedRange<>(NaicFField.NET_REINSURANCE_POSITION,      "NAIC_F_NET_REINSURANCE_POSITION"));

    @Override
    public Class<NaicFField> keyClass() {
        return NaicFField.class;
    }

    @Override
    public List<Mapping<NaicFField>> mappings() {
        return MAPPINGS;
    }
}

package com.medfund.finance.regulatory.service;

import com.medfund.shared.report.ReportKey;

import java.util.List;
import java.util.Set;

/**
 * Static registry of which Phase 16 regulator report applies to which
 * tenant. Mirrors the {@code @RequiresJurisdiction} / {@code @RequiresCountry}
 * gates that individual per-regulator controllers will carry (Phase 10-13,
 * 18, 20-21, 25). Kept in one place so the reports hub, due-date banner
 * and scanner all agree on eligibility without cross-referencing every
 * controller.
 *
 * <p>Reports with a jurisdiction gate ignore country and vice versa —
 * {@code jurisdictionCode} and {@code countryCode} are separate axes on
 * {@code public.tenants} (V131 and V001 respectively).
 */
public final class RegulatoryReportApplicability {

    private RegulatoryReportApplicability() {}

    private enum Axis { JURISDICTION, COUNTRY }

    private record Rule(Axis axis, Set<String> values) {}

    private static final java.util.Map<ReportKey, Rule> RULES = java.util.Map.ofEntries(
            java.util.Map.entry(ReportKey.IPEC_QUARTERLY_RETURN,
                    new Rule(Axis.JURISDICTION, Set.of("ZW_IPEC_SHORT_TERM"))),
            java.util.Map.entry(ReportKey.CMS_ASR,
                    new Rule(Axis.JURISDICTION, Set.of("ZA_CMS_MEDICAL_SCHEME"))),
            java.util.Map.entry(ReportKey.NAIC_SCHEDULE_P,
                    new Rule(Axis.JURISDICTION, Set.of("US_NAIC"))),
            java.util.Map.entry(ReportKey.NAIC_SCHEDULE_F,
                    new Rule(Axis.JURISDICTION, Set.of("US_NAIC"))),
            java.util.Map.entry(ReportKey.PMB_SPEND,
                    new Rule(Axis.JURISDICTION, Set.of("ZA_CMS_MEDICAL_SCHEME"))),
            java.util.Map.entry(ReportKey.VAT_RETURN,
                    new Rule(Axis.COUNTRY, Set.of("ZW", "ZA"))),
            java.util.Map.entry(ReportKey.TAX_WITHHELD_RETURN,
                    new Rule(Axis.COUNTRY, Set.of("ZW", "ZA"))),
            java.util.Map.entry(ReportKey.AML_STR,
                    new Rule(Axis.COUNTRY, Set.of("ZW", "ZA", "US")))
    );

    /** Every Phase-16 key that participates in the applicability matrix. */
    public static List<ReportKey> keys() {
        return List.copyOf(RULES.keySet());
    }

    /** Every Phase-16 report the given tenant is eligible to file. */
    public static List<ReportKey> applicableFor(String jurisdictionCode, String countryCode) {
        return RULES.entrySet().stream()
                .filter(e -> matches(e.getValue(), jurisdictionCode, countryCode))
                .map(java.util.Map.Entry::getKey)
                .sorted()
                .toList();
    }

    private static boolean matches(Rule rule, String jurisdictionCode, String countryCode) {
        return switch (rule.axis()) {
            case JURISDICTION -> jurisdictionCode != null && rule.values().contains(jurisdictionCode);
            case COUNTRY -> countryCode != null && rule.values().contains(countryCode);
        };
    }
}

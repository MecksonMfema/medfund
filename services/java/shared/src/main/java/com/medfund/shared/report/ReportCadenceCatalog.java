package com.medfund.shared.report;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * Static catalog mapping each Phase 16 regulator {@link ReportKey} to its
 * filing cadence and the number of days the tenant has after period end to
 * file. Consumed by:
 * <ul>
 *   <li>the Angular reports hub due-date banner (Phase 6)</li>
 *   <li>{@code RegulatoryDueDateScanner} cron for push notifications (Phase 8)</li>
 * </ul>
 *
 * <p>Days-post-period-end values match the published deadlines at
 * plan-drafting date (2026-08-30) — see the parent plan REG14 for sources.
 * Adjust via {@code RuleCategory.REGULATORY_PARAMETER} (Phase 15) rather
 * than editing this map in-flight.
 */
public final class ReportCadenceCatalog {

    private ReportCadenceCatalog() {}

    /** Cadence + due-date offset for a single report. */
    public record CadenceInfo(ReportCadence cadence, int daysPostPeriodEnd) {}

    private static final Map<ReportKey, CadenceInfo> MAP;

    static {
        Map<ReportKey, CadenceInfo> m = new EnumMap<>(ReportKey.class);
        m.put(ReportKey.IPEC_QUARTERLY_RETURN, new CadenceInfo(ReportCadence.QUARTERLY, 30));
        m.put(ReportKey.CMS_ASR,               new CadenceInfo(ReportCadence.ANNUAL,    180));
        m.put(ReportKey.NAIC_SCHEDULE_P,       new CadenceInfo(ReportCadence.ANNUAL,    60));
        m.put(ReportKey.NAIC_SCHEDULE_F,       new CadenceInfo(ReportCadence.ANNUAL,    60));
        m.put(ReportKey.PMB_SPEND,             new CadenceInfo(ReportCadence.ANNUAL,    180));
        m.put(ReportKey.TAX_WITHHELD_RETURN,   new CadenceInfo(ReportCadence.MONTHLY,   15));
        m.put(ReportKey.VAT_RETURN,            new CadenceInfo(ReportCadence.MONTHLY,   25));
        m.put(ReportKey.AML_STR,               new CadenceInfo(ReportCadence.QUARTERLY, 30));
        MAP = Map.copyOf(m);
    }

    public static Optional<CadenceInfo> lookup(ReportKey key) {
        return Optional.ofNullable(MAP.get(key));
    }

    /**
     * Absolute due date for a given period-end. Throws if the key is not a
     * cadenced regulator report — callers should filter on
     * {@link #lookup(ReportKey)}{@code .isPresent()} first.
     */
    public static LocalDate dueDate(ReportKey key, LocalDate periodEnd) {
        CadenceInfo info = lookup(key).orElseThrow(() ->
                new IllegalStateException("No cadence registered for " + key));
        return periodEnd.plusDays(info.daysPostPeriodEnd());
    }
}

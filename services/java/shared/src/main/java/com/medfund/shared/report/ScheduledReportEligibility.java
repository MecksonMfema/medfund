package com.medfund.shared.report;

import java.util.EnumSet;
import java.util.Set;

/**
 * Phase 17 scheduling whitelist. Not every {@code cadenced=true} key is
 * schedulable by tenants in v1 — regulator + IFRS 17 + AML periodic + FRAUD
 * keys are excluded per Phase 17 §S1 (parent plan). Those keys still carry
 * {@link ReportKey#isCadenced() cadenced=true} because the regulator due-date
 * scanner (Phase 16 REG20) uses that flag, but the tenant admin cannot pick
 * them from the Phase 17 schedule form.
 *
 * <p>This helper is the single source of truth for the whitelist:
 * tenancy-service enforces it on schedule CRUD, and Angular filters the UI
 * grid by it.
 */
public final class ScheduledReportEligibility {

    private static final Set<ReportKey> WHITELIST = EnumSet.of(
            ReportKey.COMMISSION_STATEMENT,
            ReportKey.LOSS_RATIO,
            ReportKey.COLLECTION_RATE,
            ReportKey.AGED_DEBTORS,
            ReportKey.CASH_FLOW_FORECAST_13W,
            ReportKey.CLAIMS_SUMMARY,
            ReportKey.POLICY_MOVEMENT,
            ReportKey.PERSISTENCY_COHORT,
            ReportKey.GROUP_CENSUS,
            ReportKey.PROVIDER_NETWORK_UTILIZATION,
            ReportKey.REINSURANCE_CESSION_BORDEREAU,
            ReportKey.REINSURANCE_RECOVERIES,
            ReportKey.UPR_MOVEMENT
    );

    private ScheduledReportEligibility() {}

    public static boolean isEligible(ReportKey key) {
        return WHITELIST.contains(key);
    }

    public static Set<ReportKey> whitelist() {
        return EnumSet.copyOf(WHITELIST);
    }
}

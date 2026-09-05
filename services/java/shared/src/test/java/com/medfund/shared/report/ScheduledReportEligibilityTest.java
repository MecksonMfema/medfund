package com.medfund.shared.report;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ScheduledReportEligibilityTest {

    @Test
    void whitelistContainsExactly19OperationalCadencedKeys() {
        // Phase 17 §S1 baseline (13 keys) + Phase 18 §Phase 8 K11 executive
        // KPI widening (5 keys) + Phase 19 §B Phase 12 FRAUD_SIU_REPORT
        // widening (1 key) = 19 tenant-schedulable keys.
        Set<ReportKey> expected = Set.of(
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
                ReportKey.UPR_MOVEMENT,
                ReportKey.LOSS_RATIO_KPI,
                ReportKey.EXPENSE_RATIO,
                ReportKey.COMBINED_RATIO,
                ReportKey.CLAIMS_FREQUENCY,
                ReportKey.AVERAGE_SEVERITY,
                ReportKey.FRAUD_SIU_REPORT);
        assertThat(ScheduledReportEligibility.whitelist())
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void regulatoryAndIfrsAndAmlKeysAreExcluded() {
        // Phase 17 §S1 — regulator + IFRS 17 + AML periodic keys stay out of
        // the v1 whitelist even though cadenced=true, because they depend on
        // MFA-gated human filing (REG12/REG13) or bespoke draft workflows.
        // FRAUD_SIU_REPORT was here in Phase 17 as a placeholder pending
        // Phase 19 grill — moved to WHITELIST by Phase 19 §B Phase 12 with
        // per-schedule includeSensitiveSheets gate (FR12).
        Set<ReportKey> excluded = Set.of(
                ReportKey.IFRS17_LRC_LIC_RECONCILIATION,
                ReportKey.IFRS17_INSURANCE_REVENUE_SERVICE_RESULT,
                ReportKey.IPEC_QUARTERLY_RETURN,
                ReportKey.CMS_ASR,
                ReportKey.NAIC_SCHEDULE_P,
                ReportKey.NAIC_SCHEDULE_F,
                ReportKey.PMB_SPEND,
                ReportKey.AML_STR,
                ReportKey.TAX_WITHHELD_RETURN,
                ReportKey.VAT_RETURN);
        excluded.forEach(k -> assertThat(ScheduledReportEligibility.isEligible(k))
                .as("Non-whitelisted cadenced key %s must not be eligible", k.name())
                .isFalse());
    }

    @Test
    void whitelistIsSubsetOfCadencedKeys() {
        // Whitelist rows must all carry cadenced=true — otherwise the UI grid
        // would never surface them (the reports-tab groups by cadenced flag).
        ScheduledReportEligibility.whitelist().forEach(k ->
                assertThat(k.isCadenced())
                        .as("Whitelisted key %s must be cadenced=true", k.name())
                        .isTrue());
    }

    @Test
    void whitelistReturnsDefensiveCopy() {
        Set<ReportKey> first = ScheduledReportEligibility.whitelist();
        Set<ReportKey> second = ScheduledReportEligibility.whitelist();
        assertThat(first).isNotSameAs(second);
        assertThat(first).isEqualTo(second);
    }

    @Test
    void nonCadencedKeyIsNeverEligible() {
        assertThat(ScheduledReportEligibility.isEligible(ReportKey.MEMBER_STATEMENT)).isFalse();
        assertThat(ScheduledReportEligibility.isEligible(ReportKey.CREDITORS)).isFalse();
    }
}

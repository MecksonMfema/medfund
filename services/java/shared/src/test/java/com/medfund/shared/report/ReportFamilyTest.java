package com.medfund.shared.report;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReportFamilyTest {

    @Test
    void everyFamilyExposesANonBlankLabel() {
        for (ReportFamily f : ReportFamily.values()) {
            assertThat(f.getLabel()).isNotBlank();
        }
    }

    @Test
    void labelsAreHumanReadable() {
        assertThat(ReportFamily.BILLING.getLabel()).isEqualTo("Billing");
        assertThat(ReportFamily.PAYABLES.getLabel()).isEqualTo("Payables & Creditors");
        assertThat(ReportFamily.CLAIMS_FINANCIAL.getLabel()).isEqualTo("Claims Financial");
    }

    @Test
    void hasPolicyLifecycleArm() {
        // Phase 13 §C L8 — POLICY_LIFECYCLE is its own family so the reports hub
        // renders a distinct card instead of squatting under CLAIMS_FINANCIAL.
        assertThat(ReportFamily.POLICY_LIFECYCLE.getLabel()).isEqualTo("Policy lifecycle");
    }

    @Test
    void phase16RegulatoryFamilySplit() {
        // Phase 16 §0 REG19 — REGULATORY retains IFRS 17 only; three new
        // families carve out prudential returns, tax, and compliance so the
        // reports hub renders distinct cards per bucket instead of one
        // heaving Regulatory pile.
        assertThat(ReportFamily.PRUDENTIAL.getLabel()).isEqualTo("Prudential Returns");
        assertThat(ReportFamily.TAX.getLabel()).isEqualTo("Tax");
        assertThat(ReportFamily.COMPLIANCE.getLabel()).isEqualTo("Compliance");
        assertThat(ReportFamily.REGULATORY.getLabel()).isEqualTo("Regulatory");
    }
}

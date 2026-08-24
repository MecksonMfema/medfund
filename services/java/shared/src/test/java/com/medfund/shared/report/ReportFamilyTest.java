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
}

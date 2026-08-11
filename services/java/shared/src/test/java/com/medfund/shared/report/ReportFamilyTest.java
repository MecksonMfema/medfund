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
}

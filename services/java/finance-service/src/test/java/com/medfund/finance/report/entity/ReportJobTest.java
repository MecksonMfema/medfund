package com.medfund.finance.report.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks in the {@link ReportJob} retention-class string catalogue. Values are
 * persisted verbatim in the {@code report_job.retention_class} column and read
 * by {@code ReportJobRetentionJob}, so renaming or removing one silently
 * breaks the nightly purge branch that references it.
 */
class ReportJobTest {

    @Test
    void retentionConstants_haveExpectedValues() {
        // Phase 15 §3 baseline: operational + statutory splits.
        assertThat(ReportJob.RETENTION_OPERATIONAL_90D).isEqualTo("OPERATIONAL_90D");
        assertThat(ReportJob.RETENTION_STATUTORY_7Y).isEqualTo("STATUTORY_7Y");

        // Phase 19 §A additions: fraud-flag 1y + SIU-case 7y.
        assertThat(ReportJob.RETENTION_FRAUD_FLAG_1Y).isEqualTo("FRAUD_FLAG_1Y");
        assertThat(ReportJob.RETENTION_SIU_CASE_7Y).isEqualTo("SIU_CASE_7Y");
    }
}

package com.medfund.finance.ifrs17.service;

import com.medfund.finance.report.entity.ReportJob;
import com.medfund.shared.report.ReportKey;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-tests {@link Ifrs17JobService#classifyRetention(ReportKey)} — a
 * package-private static classifier that decides the retention-class string
 * written to {@code report_job.retention_class} at parent-insert time.
 * Reachable from this test because both files sit in the same package.
 */
class Ifrs17JobServiceTest {

    @Test
    void classifyRetention_fraudSiuReport_returnsSiuCase7y() {
        // Phase 19 §A widen: FRAUD_SIU_REPORT routes to SIU_CASE_7Y so the
        // 7-year insurance-fraud statute retention applies rather than the
        // 90-day operational default that would otherwise catch a FRAUD-family
        // (non-REGULATORY) key.
        assertThat(Ifrs17JobService.classifyRetention(ReportKey.FRAUD_SIU_REPORT))
                .isEqualTo(ReportJob.RETENTION_SIU_CASE_7Y);
    }

    @Test
    void classifyRetention_regulatoryFamily_returnsStatutory7y() {
        // Baseline: IFRS 17 + regulatory reports keep their 7-year statutory
        // retention post-Phase-19.
        assertThat(Ifrs17JobService.classifyRetention(ReportKey.IFRS17_LRC_LIC_RECONCILIATION))
                .isEqualTo(ReportJob.RETENTION_STATUTORY_7Y);
    }

    @Test
    void classifyRetention_operationalKey_returnsOperational90d() {
        // Baseline: every non-REGULATORY, non-FRAUD_SIU_REPORT key stays on
        // the 90-day operational default.
        assertThat(Ifrs17JobService.classifyRetention(ReportKey.PERSISTENCY_STUDY))
                .isEqualTo(ReportJob.RETENTION_OPERATIONAL_90D);
    }
}

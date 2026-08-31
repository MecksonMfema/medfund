package com.medfund.finance.report.service;

import com.medfund.finance.report.entity.ReportJob;
import com.medfund.shared.report.ReportKey;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-level coverage for {@link ReportJobService#classifyRetention(String)}.
 * IFRS 17 / regulatory keys must map to {@code STATUTORY_7Y}; everything
 * else (actuarial / operational / billing / …) inherits the Phase 14
 * default {@code OPERATIONAL_90D} (I28). After Phase 16 §0 REG19 the
 * regulatory keys span four families — REGULATORY (IFRS 17) plus
 * PRUDENTIAL / TAX / COMPLIANCE — all of which are statutory.
 */
class ReportJobServiceTest {

    @Test
    void regulatoryFamily_mapsToStatutory7y() {
        // REGULATORY (IFRS 17)
        assertThat(ReportJobService.classifyRetention(
                ReportKey.IFRS17_LRC_LIC_RECONCILIATION.name()))
                .isEqualTo(ReportJob.RETENTION_STATUTORY_7Y);
        assertThat(ReportJobService.classifyRetention(
                ReportKey.IFRS17_INSURANCE_REVENUE_SERVICE_RESULT.name()))
                .isEqualTo(ReportJob.RETENTION_STATUTORY_7Y);
        // PRUDENTIAL (Phase 16 §A) — every regulator return keeps 7 years.
        assertThat(ReportJobService.classifyRetention(
                ReportKey.IPEC_QUARTERLY_RETURN.name()))
                .isEqualTo(ReportJob.RETENTION_STATUTORY_7Y);
        assertThat(ReportJobService.classifyRetention(
                ReportKey.NAIC_SCHEDULE_F.name()))
                .isEqualTo(ReportJob.RETENTION_STATUTORY_7Y);
        // TAX (Phase 16 §C)
        assertThat(ReportJobService.classifyRetention(
                ReportKey.VAT_RETURN.name()))
                .isEqualTo(ReportJob.RETENTION_STATUTORY_7Y);
        assertThat(ReportJobService.classifyRetention(
                ReportKey.TAX_WITHHELD_RETURN.name()))
                .isEqualTo(ReportJob.RETENTION_STATUTORY_7Y);
        // COMPLIANCE (Phase 16 §B + §D)
        assertThat(ReportJobService.classifyRetention(
                ReportKey.PMB_SPEND.name()))
                .isEqualTo(ReportJob.RETENTION_STATUTORY_7Y);
        assertThat(ReportJobService.classifyRetention(
                ReportKey.AML_STR.name()))
                .isEqualTo(ReportJob.RETENTION_STATUTORY_7Y);
    }

    @Test
    void nonRegulatoryFamily_mapsToOperational90d() {
        assertThat(ReportJobService.classifyRetention(
                ReportKey.IBNR_TRIANGLE.name()))
                .isEqualTo(ReportJob.RETENTION_OPERATIONAL_90D);
        assertThat(ReportJobService.classifyRetention(
                ReportKey.BILLING_REPORT.name()))
                .isEqualTo(ReportJob.RETENTION_OPERATIONAL_90D);
        assertThat(ReportJobService.classifyRetention(
                ReportKey.COMMISSION_STATEMENT.name()))
                .isEqualTo(ReportJob.RETENTION_OPERATIONAL_90D);
    }

    @Test
    void unknownKey_fallsBackToOperational() {
        assertThat(ReportJobService.classifyRetention("SOME_KEY_THAT_DOES_NOT_EXIST"))
                .isEqualTo(ReportJob.RETENTION_OPERATIONAL_90D);
        assertThat(ReportJobService.classifyRetention(null))
                .isEqualTo(ReportJob.RETENTION_OPERATIONAL_90D);
    }
}

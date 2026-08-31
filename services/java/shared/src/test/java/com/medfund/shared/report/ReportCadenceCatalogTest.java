package com.medfund.shared.report;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportCadenceCatalogTest {

    private static final List<ReportKey> PHASE_16_KEYS = List.of(
            ReportKey.IPEC_QUARTERLY_RETURN,
            ReportKey.CMS_ASR,
            ReportKey.NAIC_SCHEDULE_P,
            ReportKey.NAIC_SCHEDULE_F,
            ReportKey.PMB_SPEND,
            ReportKey.AML_STR,
            ReportKey.TAX_WITHHELD_RETURN,
            ReportKey.VAT_RETURN
    );

    @Test
    void everyPhase16KeyHasCadenceRegistered() {
        for (ReportKey key : PHASE_16_KEYS) {
            assertThat(ReportCadenceCatalog.lookup(key))
                    .as("cadence for %s", key)
                    .isPresent();
        }
    }

    @Test
    void nonPhase16KeyReturnsEmpty() {
        // Cadenced non-regulator reports (e.g. CLAIMS_SUMMARY) intentionally don't
        // participate in the regulator-report due-date banner or scanner.
        assertThat(ReportCadenceCatalog.lookup(ReportKey.CLAIMS_SUMMARY)).isEmpty();
        assertThat(ReportCadenceCatalog.lookup(ReportKey.BILLING_REPORT)).isEmpty();
    }

    @Test
    void ipecQuarterlyIs30DaysAfterQuarterEnd() {
        ReportCadenceCatalog.CadenceInfo info =
                ReportCadenceCatalog.lookup(ReportKey.IPEC_QUARTERLY_RETURN).orElseThrow();
        assertThat(info.cadence()).isEqualTo(ReportCadence.QUARTERLY);
        assertThat(info.daysPostPeriodEnd()).isEqualTo(30);

        LocalDate q1End = LocalDate.of(2026, 3, 31);
        assertThat(ReportCadenceCatalog.dueDate(ReportKey.IPEC_QUARTERLY_RETURN, q1End))
                .isEqualTo(LocalDate.of(2026, 4, 30));
    }

    @Test
    void cmsAsrIs180DaysAfterYearEnd() {
        ReportCadenceCatalog.CadenceInfo info =
                ReportCadenceCatalog.lookup(ReportKey.CMS_ASR).orElseThrow();
        assertThat(info.cadence()).isEqualTo(ReportCadence.ANNUAL);
        assertThat(info.daysPostPeriodEnd()).isEqualTo(180);

        assertThat(ReportCadenceCatalog.dueDate(ReportKey.CMS_ASR, LocalDate.of(2026, 12, 31)))
                .isEqualTo(LocalDate.of(2027, 6, 29));
    }

    @Test
    void naicScheduleSetIsAnnualPlus60() {
        for (ReportKey key : List.of(ReportKey.NAIC_SCHEDULE_P, ReportKey.NAIC_SCHEDULE_F)) {
            ReportCadenceCatalog.CadenceInfo info = ReportCadenceCatalog.lookup(key).orElseThrow();
            assertThat(info.cadence()).isEqualTo(ReportCadence.ANNUAL);
            assertThat(info.daysPostPeriodEnd()).isEqualTo(60);
        }
    }

    @Test
    void pmbSpendIsAnnualPlus180() {
        ReportCadenceCatalog.CadenceInfo info =
                ReportCadenceCatalog.lookup(ReportKey.PMB_SPEND).orElseThrow();
        assertThat(info.cadence()).isEqualTo(ReportCadence.ANNUAL);
        assertThat(info.daysPostPeriodEnd()).isEqualTo(180);
    }

    @Test
    void amlStrIsQuarterlyPlus30() {
        ReportCadenceCatalog.CadenceInfo info =
                ReportCadenceCatalog.lookup(ReportKey.AML_STR).orElseThrow();
        assertThat(info.cadence()).isEqualTo(ReportCadence.QUARTERLY);
        assertThat(info.daysPostPeriodEnd()).isEqualTo(30);
    }

    @Test
    void taxWithheldIsMonthlyPlus15() {
        ReportCadenceCatalog.CadenceInfo info =
                ReportCadenceCatalog.lookup(ReportKey.TAX_WITHHELD_RETURN).orElseThrow();
        assertThat(info.cadence()).isEqualTo(ReportCadence.MONTHLY);
        assertThat(info.daysPostPeriodEnd()).isEqualTo(15);

        // January 2026 return due 2026-02-15.
        assertThat(ReportCadenceCatalog.dueDate(ReportKey.TAX_WITHHELD_RETURN, LocalDate.of(2026, 1, 31)))
                .isEqualTo(LocalDate.of(2026, 2, 15));
    }

    @Test
    void vatIsMonthlyPlus25() {
        ReportCadenceCatalog.CadenceInfo info =
                ReportCadenceCatalog.lookup(ReportKey.VAT_RETURN).orElseThrow();
        assertThat(info.cadence()).isEqualTo(ReportCadence.MONTHLY);
        assertThat(info.daysPostPeriodEnd()).isEqualTo(25);
    }

    @Test
    void dueDateThrowsForUnregisteredKey() {
        assertThatThrownBy(() -> ReportCadenceCatalog.dueDate(
                ReportKey.CLAIMS_SUMMARY, LocalDate.of(2026, 1, 31)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CLAIMS_SUMMARY");
    }
}

package com.medfund.finance.reinsurance.service;

import com.medfund.shared.report.ReportPeriod;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the pure helpers on {@link BordereauReportService}.
 * The reactive envelope-composition paths are exercised end-to-end in
 * {@code BordereauReportControllerIT} where the SQL runs against real
 * Postgres — mocking a {@code DatabaseClient.GenericExecuteSpec} chain
 * here would be higher-friction than the IT and prove less.
 */
class BordereauReportServiceTest {

    @Test
    void quarterToPeriod_q1_spansJanToMarch() {
        ReportPeriod period = BordereauReportService.quarterToPeriod(2026, 1);
        assertThat(period.periodStart()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(period.periodEnd()).isEqualTo(LocalDate.of(2026, 3, 31));
        assertThat(period.grain()).isEqualTo(ReportPeriod.PeriodGrain.QUARTERLY);
    }

    @Test
    void quarterToPeriod_q4_spansOctToDec() {
        ReportPeriod period = BordereauReportService.quarterToPeriod(2026, 4);
        assertThat(period.periodStart()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(period.periodEnd()).isEqualTo(LocalDate.of(2026, 12, 31));
    }

    @Test
    void quarterToPeriod_q2_endsOnJune30_notJuly1() {
        ReportPeriod period = BordereauReportService.quarterToPeriod(2026, 2);
        assertThat(period.periodStart()).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(period.periodEnd()).isEqualTo(LocalDate.of(2026, 6, 30));
    }

    @Test
    void quarterToPeriod_q3_leapYearIndependent() {
        ReportPeriod period = BordereauReportService.quarterToPeriod(2024, 3);
        assertThat(period.periodStart()).isEqualTo(LocalDate.of(2024, 7, 1));
        assertThat(period.periodEnd()).isEqualTo(LocalDate.of(2024, 9, 30));
    }

    @Test
    void quarterToPeriod_invalidQuarter_rejects() {
        assertThatThrownBy(() -> BordereauReportService.quarterToPeriod(2026, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BordereauReportService.quarterToPeriod(2026, 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BordereauReportService.quarterToPeriod(2026, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

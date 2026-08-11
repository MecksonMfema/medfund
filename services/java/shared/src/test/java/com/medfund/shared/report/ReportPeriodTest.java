package com.medfund.shared.report;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportPeriodTest {

    @Test
    void parse_daily_grain() {
        var period = ReportPeriod.parseFromQueryParams("2026-08-11", "2026-08-11", null);
        assertThat(period.periodStart()).isEqualTo(LocalDate.of(2026, 8, 11));
        assertThat(period.periodEnd()).isEqualTo(LocalDate.of(2026, 8, 11));
        assertThat(period.grain()).isEqualTo(ReportPeriod.PeriodGrain.DAILY);
    }

    @Test
    void parse_weekly_grain() {
        var period = ReportPeriod.parseFromQueryParams("2026-08-01", "2026-08-07", null);
        assertThat(period.grain()).isEqualTo(ReportPeriod.PeriodGrain.WEEKLY);
    }

    @Test
    void parse_monthly_grain() {
        var period = ReportPeriod.parseFromQueryParams("2026-08-01", "2026-08-31", null);
        assertThat(period.grain()).isEqualTo(ReportPeriod.PeriodGrain.MONTHLY);
    }

    @Test
    void parse_quarterly_grain() {
        var period = ReportPeriod.parseFromQueryParams("2026-04-01", "2026-06-30", null);
        assertThat(period.grain()).isEqualTo(ReportPeriod.PeriodGrain.QUARTERLY);
    }

    @Test
    void parse_yearly_grain() {
        var period = ReportPeriod.parseFromQueryParams("2026-01-01", "2026-12-31", null);
        assertThat(period.grain()).isEqualTo(ReportPeriod.PeriodGrain.YEARLY);
    }

    @Test
    void parse_explicitGrainWins() {
        var period = ReportPeriod.parseFromQueryParams("2026-08-01", "2026-08-31", "CUSTOM");
        assertThat(period.grain()).isEqualTo(ReportPeriod.PeriodGrain.CUSTOM);
    }

    @Test
    void parse_explicitGrainIsCaseInsensitive() {
        var period = ReportPeriod.parseFromQueryParams("2026-08-01", "2026-08-31", "yearly");
        assertThat(period.grain()).isEqualTo(ReportPeriod.PeriodGrain.YEARLY);
    }

    @Test
    void parse_trimsInputs() {
        var period = ReportPeriod.parseFromQueryParams(" 2026-08-01 ", " 2026-08-07 ", null);
        assertThat(period.periodStart()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(period.periodEnd()).isEqualTo(LocalDate.of(2026, 8, 7));
    }

    @Test
    void parse_missingStartFails() {
        assertThatThrownBy(() -> ReportPeriod.parseFromQueryParams(null, "2026-08-07", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReportPeriod.parseFromQueryParams("", "2026-08-07", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parse_missingEndFails() {
        assertThatThrownBy(() -> ReportPeriod.parseFromQueryParams("2026-08-01", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReportPeriod.parseFromQueryParams("2026-08-01", "  ", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parse_endBeforeStartFails() {
        assertThatThrownBy(() -> ReportPeriod.parseFromQueryParams("2026-08-07", "2026-08-01", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void periodGrainValuesIsExhaustive() {
        assertThat(ReportPeriod.PeriodGrain.values()).containsExactly(
                ReportPeriod.PeriodGrain.DAILY,
                ReportPeriod.PeriodGrain.WEEKLY,
                ReportPeriod.PeriodGrain.MONTHLY,
                ReportPeriod.PeriodGrain.QUARTERLY,
                ReportPeriod.PeriodGrain.YEARLY,
                ReportPeriod.PeriodGrain.CUSTOM);
    }
}

package com.medfund.finance.report.schedule;

import com.medfund.shared.report.ReportPeriodShape;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PeriodResolverTest {

    private static final ZoneId UTC = ZoneOffset.UTC;

    @Test
    void asOfFireTime_returnsTripleOfToday() {
        OffsetDateTime firedAt = OffsetDateTime.parse("2026-08-31T08:00:00Z");
        PeriodResolver.PeriodTriple triple = PeriodResolver.resolve(
                ReportPeriodShape.AS_OF_FIRE_TIME, "MONTHLY", firedAt, UTC);
        assertThat(triple.periodStart()).isEqualTo(LocalDate.of(2026, 8, 31));
        assertThat(triple.periodEnd()).isEqualTo(LocalDate.of(2026, 8, 31));
        assertThat(triple.asOf()).isEqualTo(LocalDate.of(2026, 8, 31));
    }

    @Test
    void previousComplete_weekly_returnsLastMondayThroughSunday() {
        // 2026-09-02 is a Wednesday — previous complete week is
        // Mon 2026-08-24 through Sun 2026-08-30.
        OffsetDateTime firedAt = OffsetDateTime.parse("2026-09-02T08:00:00Z");
        PeriodResolver.PeriodTriple triple = PeriodResolver.resolve(
                ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD, "WEEKLY", firedAt, UTC);
        assertThat(triple.periodStart()).isEqualTo(LocalDate.of(2026, 8, 24));
        assertThat(triple.periodEnd()).isEqualTo(LocalDate.of(2026, 8, 30));
        assertThat(triple.asOf()).isEqualTo(LocalDate.of(2026, 9, 2));
    }

    @Test
    void previousComplete_monthly_returnsLastFullMonth() {
        OffsetDateTime firedAt = OffsetDateTime.parse("2026-09-01T08:00:00Z");
        PeriodResolver.PeriodTriple triple = PeriodResolver.resolve(
                ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD, "MONTHLY", firedAt, UTC);
        assertThat(triple.periodStart()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(triple.periodEnd()).isEqualTo(LocalDate.of(2026, 8, 31));
    }

    @Test
    void previousComplete_quarterly_returnsLastFullQuarter() {
        // Firing on 2026-10-01 → previous quarter is Q3 (Jul..Sep 2026).
        OffsetDateTime firedAt = OffsetDateTime.parse("2026-10-01T08:00:00Z");
        PeriodResolver.PeriodTriple triple = PeriodResolver.resolve(
                ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD, "QUARTERLY", firedAt, UTC);
        assertThat(triple.periodStart()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(triple.periodEnd()).isEqualTo(LocalDate.of(2026, 9, 30));
    }

    @Test
    void previousComplete_annual_returnsLastFullYear() {
        OffsetDateTime firedAt = OffsetDateTime.parse("2026-01-01T08:00:00Z");
        PeriodResolver.PeriodTriple triple = PeriodResolver.resolve(
                ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD, "ANNUAL", firedAt, UTC);
        assertThat(triple.periodStart()).isEqualTo(LocalDate.of(2025, 1, 1));
        assertThat(triple.periodEnd()).isEqualTo(LocalDate.of(2025, 12, 31));
    }

    @Test
    void previousComplete_unknownCadence_throws() {
        OffsetDateTime firedAt = OffsetDateTime.parse("2026-08-31T08:00:00Z");
        assertThatThrownBy(() -> PeriodResolver.resolve(
                ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD, "FORTNIGHTLY", firedAt, UTC))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown cadence");
    }

    @Test
    void tenantZone_isRespected() {
        // 2026-09-01T00:30:00Z is still 2026-08-31 in America/Los_Angeles (UTC-7).
        OffsetDateTime firedAt = OffsetDateTime.parse("2026-09-01T00:30:00Z");
        PeriodResolver.PeriodTriple triple = PeriodResolver.resolve(
                ReportPeriodShape.AS_OF_FIRE_TIME, "MONTHLY", firedAt,
                ZoneId.of("America/Los_Angeles"));
        assertThat(triple.asOf()).isEqualTo(LocalDate.of(2026, 8, 31));
    }
}

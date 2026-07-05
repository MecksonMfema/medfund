package com.medfund.contributions.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the projected-cycle math for {@link BillingService#chargePreview}.
 * The whole feature's semantic ("preview next month's charge") lives in
 * this six-line helper — a silent flip to the current month would show
 * operators already-billed numbers instead of a projection. Cases below
 * cover the edge cases people get wrong: month-end anchors, year rollovers,
 * leap-year February, and short months.
 */
class BillingServiceProjectedPeriodTest {

    @Test
    void midMonthAnchor_returnsFirstAndLastOfNextMonth() {
        // Baseline case: anchor is a plain mid-month day; next cycle is
        // straightforwardly the full next month.
        LocalDate[] window = BillingService.computeProjectedPeriod(LocalDate.of(2026, 7, 15));
        assertThat(window[0]).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(window[1]).isEqualTo(LocalDate.of(2026, 8, 31));
    }

    @Test
    void anchorOnFirstOfMonth_stillPointsAtNextMonth() {
        // Regression guard: an operator opening the page on day 1 must
        // not see the CURRENT month treated as "next" — the whole
        // feature is a forward projection.
        LocalDate[] window = BillingService.computeProjectedPeriod(LocalDate.of(2026, 7, 1));
        assertThat(window[0]).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(window[1]).isEqualTo(LocalDate.of(2026, 8, 31));
    }

    @Test
    void anchorOnLastDayOfMonth_pointsAtNextMonth() {
        // An anchor on Jul 31 should still resolve to August, not
        // September — plusMonths(1).withDayOfMonth(1) is idempotent on
        // the month arithmetic, but this test proves the withDayOfMonth
        // reset happens after plusMonths (matters for 31 → 30-day months).
        LocalDate[] window = BillingService.computeProjectedPeriod(LocalDate.of(2026, 7, 31));
        assertThat(window[0]).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(window[1]).isEqualTo(LocalDate.of(2026, 8, 31));
    }

    @Test
    void december_rollsIntoJanuaryOfFollowingYear() {
        // Year rollover — the naive "plusMonths(1)" hides this arithmetic
        // but a regression to withDayOfYear or manual month math would
        // break at the boundary. Explicit December test guards it.
        LocalDate[] window = BillingService.computeProjectedPeriod(LocalDate.of(2026, 12, 20));
        assertThat(window[0]).isEqualTo(LocalDate.of(2027, 1, 1));
        assertThat(window[1]).isEqualTo(LocalDate.of(2027, 1, 31));
    }

    @Test
    void anchorInJanuary_ofLeapYear_projectsIntoFebruary29() {
        // February 2028 has 29 days (leap year). The
        // "plusMonths(1).minusDays(1)" derivation must return Feb 29,
        // not Feb 28 — otherwise the last day would be off by one and
        // any date-range join on periodEnd (e.g., age_group_prices)
        // could exclude a same-day-effective row.
        LocalDate[] window = BillingService.computeProjectedPeriod(LocalDate.of(2028, 1, 10));
        assertThat(window[0]).isEqualTo(LocalDate.of(2028, 2, 1));
        assertThat(window[1]).isEqualTo(LocalDate.of(2028, 2, 29));
    }

    @Test
    void anchorInJanuary_ofNonLeapYear_projectsIntoFebruary28() {
        // Symmetry check with the leap-year case — Feb 2026 has 28 days,
        // and the helper must respect that.
        LocalDate[] window = BillingService.computeProjectedPeriod(LocalDate.of(2026, 1, 10));
        assertThat(window[0]).isEqualTo(LocalDate.of(2026, 2, 1));
        assertThat(window[1]).isEqualTo(LocalDate.of(2026, 2, 28));
    }

    @Test
    void anchorInMarch_projectsIntoAprilThirty() {
        // Any 30-day month must not silently roll to 31. Guards against
        // a mistaken plusDays(30) shortcut.
        LocalDate[] window = BillingService.computeProjectedPeriod(LocalDate.of(2026, 3, 5));
        assertThat(window[0]).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(window[1]).isEqualTo(LocalDate.of(2026, 4, 30));
    }

    @Test
    void periodEnd_isAlwaysAfterPeriodStart() {
        // Round-trip guard across a full year. A regression that
        // accidentally makes periodEnd < periodStart on any month
        // (integer underflow, wrong minusDays value, …) fails here for
        // every anchor.
        for (int month = 1; month <= 12; month++) {
            LocalDate anchor = LocalDate.of(2026, month, 15);
            LocalDate[] window = BillingService.computeProjectedPeriod(anchor);
            assertThat(window[1])
                    .as("periodEnd must be after periodStart for anchor month=" + month)
                    .isAfter(window[0]);
        }
    }
}

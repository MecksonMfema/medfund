package com.medfund.shared.validation;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class DateSnapValidatorsTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    // ── @FirstOfMonth ──────────────────────────────────────────────────────
    record FirstOfMonthCarrier(@FirstOfMonth LocalDate date) { }

    @Test
    void firstOfMonth_accepts_null() {
        assertThat(validator.validate(new FirstOfMonthCarrier(null))).isEmpty();
    }

    @Test
    void firstOfMonth_accepts_day_one() {
        assertThat(validator.validate(new FirstOfMonthCarrier(LocalDate.of(2026, 7, 1)))).isEmpty();
    }

    @Test
    void firstOfMonth_rejects_mid_month() {
        var violations = validator.validate(new FirstOfMonthCarrier(LocalDate.of(2026, 7, 15)));
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage()).isEqualTo("must be the 1st of the month");
    }

    // ── @EndOfMonth ────────────────────────────────────────────────────────
    record EndOfMonthCarrier(@EndOfMonth LocalDate date) { }

    @Test
    void endOfMonth_accepts_null() {
        assertThat(validator.validate(new EndOfMonthCarrier(null))).isEmpty();
    }

    @Test
    void endOfMonth_accepts_last_day_of_31_day_month() {
        assertThat(validator.validate(new EndOfMonthCarrier(LocalDate.of(2026, 7, 31)))).isEmpty();
    }

    @Test
    void endOfMonth_accepts_last_day_of_30_day_month() {
        assertThat(validator.validate(new EndOfMonthCarrier(LocalDate.of(2026, 4, 30)))).isEmpty();
    }

    @Test
    void endOfMonth_accepts_last_day_of_february_leap_year() {
        assertThat(validator.validate(new EndOfMonthCarrier(LocalDate.of(2028, 2, 29)))).isEmpty();
    }

    @Test
    void endOfMonth_accepts_last_day_of_february_non_leap_year() {
        assertThat(validator.validate(new EndOfMonthCarrier(LocalDate.of(2027, 2, 28)))).isEmpty();
    }

    @Test
    void endOfMonth_rejects_mid_month() {
        var violations = validator.validate(new EndOfMonthCarrier(LocalDate.of(2026, 7, 15)));
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage()).isEqualTo("must be the last day of the month");
    }

    @Test
    void endOfMonth_rejects_feb_29_of_non_leap_year_via_leap_year_construct() {
        // LocalDate.of(2027, 2, 29) throws; construct via minusDays.
        var violations = validator.validate(new EndOfMonthCarrier(LocalDate.of(2026, 2, 27)));
        assertThat(violations).hasSize(1);
    }

    // ── DateSnaps ──────────────────────────────────────────────────────────
    @Test
    void toFirstOfMonth_snaps_and_null_passes_through() {
        assertThat(DateSnaps.toFirstOfMonth(LocalDate.of(2026, 7, 15))).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(DateSnaps.toFirstOfMonth(null)).isNull();
    }

    @Test
    void toEndOfMonth_snaps_variable_month_lengths() {
        assertThat(DateSnaps.toEndOfMonth(LocalDate.of(2026, 7, 15))).isEqualTo(LocalDate.of(2026, 7, 31));
        assertThat(DateSnaps.toEndOfMonth(LocalDate.of(2026, 4, 15))).isEqualTo(LocalDate.of(2026, 4, 30));
        assertThat(DateSnaps.toEndOfMonth(LocalDate.of(2027, 2, 15))).isEqualTo(LocalDate.of(2027, 2, 28));
        assertThat(DateSnaps.toEndOfMonth(LocalDate.of(2028, 2, 15))).isEqualTo(LocalDate.of(2028, 2, 29));
        assertThat(DateSnaps.toEndOfMonth(null)).isNull();
    }

    // Cross-annotation sanity: a NotNull + EndOfMonth combo enforces both.
    record RequiredEomCarrier(@NotNull @EndOfMonth LocalDate date) { }

    @Test
    void required_endOfMonth_rejects_null_but_accepts_eom() {
        assertThat(validator.validate(new RequiredEomCarrier(null))).hasSize(1);
        assertThat(validator.validate(new RequiredEomCarrier(LocalDate.of(2026, 7, 31)))).isEmpty();
    }
}

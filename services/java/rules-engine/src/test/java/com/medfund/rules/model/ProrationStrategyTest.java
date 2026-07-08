package com.medfund.rules.model;

import com.medfund.rules.fact.SchemeChangeContext;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Golden numbers for each strategy against a canonical mid-year upgrade:
 * Scheme A limit 1000, consumed 800 before change; Scheme B limit 3000.
 * Change effective 2026-07-01 → daysRemainingInYear = 184, daysInYear = 365.
 * consumedUnderNewScheme = 0 (nothing filed yet under B).
 */
class ProrationStrategyTest {

    private static final BigDecimal PREV_LIMIT = new BigDecimal("1000");
    private static final BigDecimal NEW_LIMIT = new BigDecimal("3000");
    private static final BigDecimal CONSUMED_PREV = new BigDecimal("800");

    private SchemeChangeContext ctx() {
        SchemeChangeContext c = new SchemeChangeContext();
        c.setPrevAnnualLimit(PREV_LIMIT);
        c.setNewAnnualLimit(NEW_LIMIT);
        c.setConsumedUnderPrevScheme(CONSUMED_PREV);
        c.setConsumedUnderNewScheme(BigDecimal.ZERO);
        c.setEffectiveDate(LocalDate.of(2026, 7, 1));
        c.setDaysSinceChange(0);
        c.setDaysRemainingInYear(184);
        c.setDaysInYear(365);
        c.setChangeKind("UPGRADE");
        return c;
    }

    @Test
    void none_returnsNewLimit() {
        assertThat(ProrationStrategy.NONE.effectiveLimit(ctx(), NEW_LIMIT))
                .isEqualByComparingTo(NEW_LIMIT);
    }

    @Test
    void deltaCredit_subtractsTotalConsumed() {
        // 3000 − 800 = 2200
        assertThat(ProrationStrategy.DELTA_CREDIT.effectiveLimit(ctx(), NEW_LIMIT))
                .isEqualByComparingTo(new BigDecimal("2200"));
    }

    @Test
    void ratioCarry_scalesOldRemainingProportion() {
        // (1000 − 800) / 1000 = 0.2  →  0.2 × 3000 = 600
        BigDecimal result = ProrationStrategy.RATIO_CARRY.effectiveLimit(ctx(), NEW_LIMIT);
        assertThat(result.doubleValue()).isCloseTo(600.0, within(0.01));
    }

    @Test
    void calendar_scalesByRemainingYearFraction() {
        // 3000 × (184/365) ≈ 1512.33
        BigDecimal result = ProrationStrategy.CALENDAR.effectiveLimit(ctx(), NEW_LIMIT);
        assertThat(result.doubleValue()).isCloseTo(1512.33, within(0.01));
    }

    @Test
    void splitYear_returnsNewSchemeBudgetFraction() {
        // Same shape as CALENDAR for the NEW-scheme budget; old budget handled separately in the service.
        BigDecimal result = ProrationStrategy.SPLIT_YEAR.effectiveLimit(ctx(), NEW_LIMIT);
        assertThat(result.doubleValue()).isCloseTo(1512.33, within(0.01));
    }

    @Test
    void waitingPeriod_capsAtOldLimitWhenIncrementLocked() {
        // Increment (3000 − 1000 = 2000) locked until wait elapses;
        // effective = oldLimit − totalConsumed = 1000 − 800 = 200
        assertThat(ProrationStrategy.WAITING_PERIOD_ON_INCREMENT.effectiveLimit(ctx(), NEW_LIMIT))
                .isEqualByComparingTo(new BigDecimal("200"));
    }

    @Test
    void hybridByDirection_isPlaceholder_returnsNewLimitUnchanged() {
        assertThat(ProrationStrategy.HYBRID_BY_DIRECTION.effectiveLimit(ctx(), NEW_LIMIT))
                .isEqualByComparingTo(NEW_LIMIT);
    }

    @Test
    void deltaCredit_floorsAtZeroOnDowngradeWithOverconsumption() {
        SchemeChangeContext c = ctx();
        c.setNewAnnualLimit(new BigDecimal("500")); // downgrade below already-consumed
        assertThat(ProrationStrategy.DELTA_CREDIT.effectiveLimit(c, new BigDecimal("500")))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void waitingPeriod_degradesToDeltaCreditWhenNotAnIncrement() {
        // Downgrade — new < old → strategy switches to delta credit
        SchemeChangeContext c = ctx();
        c.setNewAnnualLimit(new BigDecimal("900"));
        // 900 − 800 = 100
        assertThat(ProrationStrategy.WAITING_PERIOD_ON_INCREMENT.effectiveLimit(c, new BigDecimal("900")))
                .isEqualByComparingTo(new BigDecimal("100"));
    }
}

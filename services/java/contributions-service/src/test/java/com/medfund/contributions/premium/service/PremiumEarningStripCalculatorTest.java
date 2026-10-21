package com.medfund.contributions.premium.service;

import com.medfund.contributions.premium.entity.EarningSchedule;
import com.medfund.rules.fact.PremiumFact;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks in the strip-splitting invariants that Phase 12 §A U11 depends on:
 * every strip sums exactly to {@code writtenPremium}, per-period bounds
 * clip to the coverage window, and each method (DAILY_LINEAR / MONTHLY_24THS
 * / LINEAR_WITH_LOADING) picks a distinct shape.
 */
class PremiumEarningStripCalculatorTest {

    private final PremiumEarningStripCalculator calc = new PremiumEarningStripCalculator();

    @Test
    @DisplayName("DAILY_LINEAR — 12-month annual policy sums exactly to written")
    void dailyLinear_annualPolicy_sumsExactly() {
        List<EarningSchedule> rows = calc.split(fact("1200", "DAILY_LINEAR",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), null));

        assertThat(rows).hasSize(12);
        BigDecimal sum = rows.stream().map(EarningSchedule::getWrittenAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo("1200");
        // Every period is a whole month clipped to coverage window.
        assertThat(rows.get(0).getPeriodStart()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(rows.get(11).getPeriodEnd()).isEqualTo(LocalDate.of(2026, 12, 31));
    }

    @Test
    @DisplayName("DAILY_LINEAR — mid-month start clips first period + still sums to written")
    void dailyLinear_midMonthStart_stillSumsExactly() {
        List<EarningSchedule> rows = calc.split(fact("1000", "DAILY_LINEAR",
                LocalDate.of(2026, 3, 15), LocalDate.of(2026, 12, 31), null));

        BigDecimal sum = rows.stream().map(EarningSchedule::getWrittenAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo("1000");
        assertThat(rows.get(0).getPeriodStart()).isEqualTo(LocalDate.of(2026, 3, 15));
        assertThat(rows.get(0).getPeriodEnd()).isEqualTo(LocalDate.of(2026, 3, 31));
    }

    @Test
    @DisplayName("MONTHLY_24THS — first + last half-share, middle full-share, total = written")
    void monthly24ths_hasHalfSharesAtEnds() {
        List<EarningSchedule> rows = calc.split(fact("1200", "MONTHLY_24THS",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), null));

        assertThat(rows).hasSize(12);
        BigDecimal firstAmount = rows.get(0).getWrittenAmount();
        BigDecimal lastAmount = rows.get(11).getWrittenAmount();
        BigDecimal middleAmount = rows.get(5).getWrittenAmount();
        assertThat(firstAmount).isEqualByComparingTo(lastAmount);
        // Middle should be roughly twice the half-share (allowing for rounding drift absorbed in last row).
        assertThat(middleAmount).isGreaterThan(firstAmount);
        BigDecimal sum = rows.stream().map(EarningSchedule::getWrittenAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo("1200");
    }

    @Test
    @DisplayName("LINEAR_WITH_LOADING — 15% front-loaded first month, remainder linear, total = written")
    void linearWithLoading_frontLoadsFirstPeriod() {
        List<EarningSchedule> rows = calc.split(fact("1200", "LINEAR_WITH_LOADING",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), new BigDecimal("15")));

        BigDecimal first = rows.get(0).getWrittenAmount();
        BigDecimal middle = rows.get(5).getWrittenAmount();
        assertThat(first).isGreaterThan(middle);
        BigDecimal sum = rows.stream().map(EarningSchedule::getWrittenAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo("1200");
    }

    @Test
    @DisplayName("Single-month coverage — one row equal to written")
    void singleMonth_singleRow() {
        List<EarningSchedule> rows = calc.split(fact("600", "DAILY_LINEAR",
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30), null));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getWrittenAmount()).isEqualByComparingTo("600");
    }

    @Test
    @DisplayName("Cross-year coverage — 13 rows for a Feb-to-Feb policy, sums exactly")
    void crossYear_stripsAcrossCalendarBoundary() {
        List<EarningSchedule> rows = calc.split(fact("1300", "DAILY_LINEAR",
                LocalDate.of(2026, 2, 15), LocalDate.of(2027, 2, 14), null));

        // Feb 2026 → Feb 2027 straddles 13 calendar months.
        assertThat(rows).hasSize(13);
        BigDecimal sum = rows.stream().map(EarningSchedule::getWrittenAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo("1300");
    }

    @Test
    @DisplayName("Missing coverage bounds → empty strip (guard for legacy_no_premium rows)")
    void missingBounds_emptyStrip() {
        PremiumFact fact = new PremiumFact();
        fact.setWrittenPremium(new BigDecimal("500"));
        // Coverage dates left null on purpose.
        assertThat(calc.split(fact)).isEmpty();
    }

    @Test
    @DisplayName("Unknown earning method falls through to DAILY_LINEAR shape")
    void unknownMethod_fallsBackToLinear() {
        List<EarningSchedule> rows = calc.split(fact("900", "SOMETHING_WEIRD",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), null));

        BigDecimal sum = rows.stream().map(EarningSchedule::getWrittenAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo("900");
        // All rows carry the (unknown-but-passed-through) method label so the
        // audit trail still records what the rule requested.
        assertThat(rows.get(0).getEarningMethod()).isEqualTo("SOMETHING_WEIRD");
    }

    private static PremiumFact fact(String premium, String method, LocalDate start, LocalDate end,
                                    BigDecimal loading) {
        PremiumFact f = new PremiumFact();
        f.setPolicyId(UUID.randomUUID().toString());
        f.setPolicySource("LIFE_POLICY");
        f.setInsuranceLine("LIFE");
        f.setTenantId(UUID.randomUUID().toString());
        f.setWrittenPremium(new BigDecimal(premium));
        f.setCurrencyCode("USD");
        f.setCoverageStart(start);
        f.setCoverageEnd(end);
        f.setEarningMethod(method);
        f.setLoadingPercent(loading);
        return f;
    }
}

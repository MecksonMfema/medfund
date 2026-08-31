package com.medfund.finance.regulatory.aml;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-function tests for {@link AmlSummaryCalculator}. Covers the two
 * derivations that matter: total activity aggregation + filed-rate
 * ratio (with divide-by-zero guard). Rounding follows HALF_UP 2dp for
 * amounts and HALF_UP 4dp for the ratio — deliberately unified with the
 * VAT / WHT / PMB calculators so a single "how do regulator numbers
 * round?" invariant lives across the report suite.
 */
class AmlSummaryCalculatorTest {

    private final AmlSummaryCalculator calculator = new AmlSummaryCalculator();

    @Test
    void compute_zeroInput_producesAllZeros_withHalfUpRounding() {
        AmlSummaryCalculator.Computed c = calculator.compute(
                new AmlSummaryRawData("entity", "ref", null, null, null, null),
                AmlThresholds.empty());

        assertThat(c.getAllAboveThresholdCount()).isEqualTo(0L);
        assertThat(c.getAllAboveThresholdTotal()).isEqualByComparingTo("0.00");
        assertThat(c.getFiledRate()).isEqualByComparingTo("0.0000");
        assertThat(c.getStrFiledTotalAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    void compute_fixtureMatchesGoldenValues() {
        // Mirrors regulatory-fixtures/aml/aml-summary-golden.yaml
        Map<AmlSummaryRawData.ActivityCategory, Long> counts = new EnumMap<>(AmlSummaryRawData.ActivityCategory.class);
        counts.put(AmlSummaryRawData.ActivityCategory.PREMIUM,         15L);
        counts.put(AmlSummaryRawData.ActivityCategory.CLAIM_PAYOUT,     8L);
        counts.put(AmlSummaryRawData.ActivityCategory.ADVANCE_PAYMENT,  3L);
        counts.put(AmlSummaryRawData.ActivityCategory.COMMISSION,       4L);
        counts.put(AmlSummaryRawData.ActivityCategory.OTHER,            0L);

        Map<AmlSummaryRawData.ActivityCategory, BigDecimal> totals = new EnumMap<>(AmlSummaryRawData.ActivityCategory.class);
        totals.put(AmlSummaryRawData.ActivityCategory.PREMIUM,         new BigDecimal("3500000.00"));
        totals.put(AmlSummaryRawData.ActivityCategory.CLAIM_PAYOUT,    new BigDecimal("1200000.00"));
        totals.put(AmlSummaryRawData.ActivityCategory.ADVANCE_PAYMENT, new BigDecimal("450000.00"));
        totals.put(AmlSummaryRawData.ActivityCategory.COMMISSION,      new BigDecimal("80000.00"));
        totals.put(AmlSummaryRawData.ActivityCategory.OTHER,           new BigDecimal("0.00"));

        Map<AmlSummaryRawData.StrStatus, Long> str = new EnumMap<>(AmlSummaryRawData.StrStatus.class);
        str.put(AmlSummaryRawData.StrStatus.RAISED,   12L);
        str.put(AmlSummaryRawData.StrStatus.REVIEWED, 10L);
        str.put(AmlSummaryRawData.StrStatus.FILED,     6L);
        str.put(AmlSummaryRawData.StrStatus.CLOSED,    4L);

        AmlSummaryCalculator.Computed c = calculator.compute(
                new AmlSummaryRawData("Acme Insurance ZA (Pty) Ltd",
                        "FIC-REG-2026-0000042",
                        counts, totals, str, new BigDecimal("875000.00")),
                AmlThresholds.empty());

        assertThat(c.getAllAboveThresholdCount()).isEqualTo(30L);
        assertThat(c.getAllAboveThresholdTotal()).isEqualByComparingTo("5230000.00");
        // 6 / 30 = 0.2000
        assertThat(c.getFiledRate()).isEqualByComparingTo("0.2000");
        assertThat(c.getStrFiledTotalAmount()).isEqualByComparingTo("875000.00");
    }

    @Test
    void compute_filedButZeroActivity_ratioIsZero_notNaN() {
        // No above-threshold transactions, but STR filings still counted —
        // divide-by-zero must yield 0.0000 not throw / not NaN.
        Map<AmlSummaryRawData.StrStatus, Long> str = new EnumMap<>(AmlSummaryRawData.StrStatus.class);
        str.put(AmlSummaryRawData.StrStatus.FILED, 3L);
        AmlSummaryCalculator.Computed c = calculator.compute(
                new AmlSummaryRawData("e", "r", null, null, str, BigDecimal.ZERO),
                AmlThresholds.empty());

        assertThat(c.getAllAboveThresholdCount()).isEqualTo(0L);
        assertThat(c.getFiledRate()).isEqualByComparingTo("0.0000");
    }

    @Test
    void compute_thresholdMirroring_appliesForEveryCategory() {
        Map<AmlSummaryRawData.ActivityCategory, BigDecimal> byCategory =
                new EnumMap<>(AmlSummaryRawData.ActivityCategory.class);
        byCategory.put(AmlSummaryRawData.ActivityCategory.PREMIUM, new BigDecimal("25000.00"));
        byCategory.put(AmlSummaryRawData.ActivityCategory.CLAIM_PAYOUT, new BigDecimal("25000.00"));

        AmlSummaryCalculator.Computed c = calculator.compute(
                new AmlSummaryRawData("e", "r", null, null, null, null),
                new AmlThresholds(byCategory));

        assertThat(c.getThresholds().get(AmlSummaryRawData.ActivityCategory.PREMIUM))
                .isEqualByComparingTo("25000.00");
        assertThat(c.getThresholds().get(AmlSummaryRawData.ActivityCategory.OTHER))
                .as("unset category defaults to 0 (obvious 'threshold not configured' signal)")
                .isEqualByComparingTo("0");
    }

    @Test
    void compute_ratio_roundsHalfUpTo4dp() {
        // 1 / 3 = 0.3333333… → HALF_UP 4dp → 0.3333
        Map<AmlSummaryRawData.ActivityCategory, Long> counts = new EnumMap<>(AmlSummaryRawData.ActivityCategory.class);
        counts.put(AmlSummaryRawData.ActivityCategory.PREMIUM, 3L);
        Map<AmlSummaryRawData.StrStatus, Long> str = new EnumMap<>(AmlSummaryRawData.StrStatus.class);
        str.put(AmlSummaryRawData.StrStatus.FILED, 1L);

        AmlSummaryCalculator.Computed c = calculator.compute(
                new AmlSummaryRawData("e", "r", counts, null, str, BigDecimal.ZERO),
                AmlThresholds.empty());

        assertThat(c.getFiledRate()).isEqualByComparingTo("0.3333");
    }
}

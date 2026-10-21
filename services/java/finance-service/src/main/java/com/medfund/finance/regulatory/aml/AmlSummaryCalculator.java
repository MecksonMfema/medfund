package com.medfund.finance.regulatory.aml;

import lombok.Getter;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumMap;
import java.util.Map;

/**
 * Derives the AML/STR summary metrics from {@link AmlSummaryRawData} +
 * {@link AmlThresholds}. Pure function — no I/O, no reactive plumbing;
 * the shaper wires the pieces together via {@code Mono.zip}.
 *
 * <p>Totals:
 * <ul>
 *   <li>{@link Computed#allAboveThresholdCount} — sum of the five
 *       category counts.</li>
 *   <li>{@link Computed#allAboveThresholdTotal} — sum of the five
 *       category amounts (HALF_UP 2dp for consistency with the XLSX
 *       display).</li>
 *   <li>{@link Computed#filedRate} — {@code STR_FILED / ALL_ABOVE_THRESHOLD}
 *       as a 4dp HALF_UP ratio; divide-by-zero → BigDecimal.ZERO to keep
 *       the XLSX numeric (and the ratio is 0 by construction if the
 *       denominator is 0 anyway).</li>
 * </ul>
 *
 * <p>Threshold values are informational for the periodic summary — they
 * appear in the {@code THRESHOLD_*} section of the report so the
 * regulator can see the tenant's stated policy. Whether a transaction
 * count is "above threshold" is a decision the raw-data provider makes
 * per-row before aggregation, not the calculator.
 */
@Component
public class AmlSummaryCalculator {

    private static final int RATE_SCALE = 4;
    private static final int AMOUNT_SCALE = 2;

    public Computed compute(AmlSummaryRawData raw, AmlThresholds thresholds) {
        Map<AmlSummaryRawData.ActivityCategory, Long> counts =
                new EnumMap<>(AmlSummaryRawData.ActivityCategory.class);
        Map<AmlSummaryRawData.ActivityCategory, BigDecimal> totals =
                new EnumMap<>(AmlSummaryRawData.ActivityCategory.class);
        long allCount = 0L;
        BigDecimal allTotal = BigDecimal.ZERO;
        for (AmlSummaryRawData.ActivityCategory cat : AmlSummaryRawData.ActivityCategory.values()) {
            long c = raw.aboveThresholdCount().getOrDefault(cat, 0L);
            BigDecimal t = raw.aboveThresholdTotal().getOrDefault(cat, BigDecimal.ZERO);
            counts.put(cat, c);
            totals.put(cat, roundAmount(t));
            allCount += c;
            allTotal = allTotal.add(t);
        }

        long strFiled = raw.strCountByStatus().getOrDefault(AmlSummaryRawData.StrStatus.FILED, 0L);
        BigDecimal filedRate = allCount == 0L
                ? BigDecimal.ZERO.setScale(RATE_SCALE, RoundingMode.HALF_UP)
                : new BigDecimal(strFiled).divide(new BigDecimal(allCount), RATE_SCALE, RoundingMode.HALF_UP);

        Map<AmlSummaryRawData.ActivityCategory, BigDecimal> thresholdByCat =
                new EnumMap<>(AmlSummaryRawData.ActivityCategory.class);
        for (AmlSummaryRawData.ActivityCategory cat : AmlSummaryRawData.ActivityCategory.values()) {
            thresholdByCat.put(cat, thresholds.threshold(cat));
        }

        Map<AmlSummaryRawData.StrStatus, Long> statusCounts =
                new EnumMap<>(AmlSummaryRawData.StrStatus.class);
        for (AmlSummaryRawData.StrStatus s : AmlSummaryRawData.StrStatus.values()) {
            statusCounts.put(s, raw.strCountByStatus().getOrDefault(s, 0L));
        }

        return new Computed(
                thresholdByCat,
                counts,
                totals,
                allCount,
                roundAmount(allTotal),
                statusCounts,
                roundAmount(raw.strFiledTotalAmount()),
                filedRate);
    }

    private static BigDecimal roundAmount(BigDecimal v) {
        return v == null
                ? BigDecimal.ZERO.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP)
                : v.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
    }

    /** Fully derived AML summary — inputs into the {@link AmlSummaryReportShaper} compose step. */
    @Getter
    public static final class Computed {
        private final Map<AmlSummaryRawData.ActivityCategory, BigDecimal> thresholds;
        private final Map<AmlSummaryRawData.ActivityCategory, Long> aboveThresholdCount;
        private final Map<AmlSummaryRawData.ActivityCategory, BigDecimal> aboveThresholdTotal;
        private final long allAboveThresholdCount;
        private final BigDecimal allAboveThresholdTotal;
        private final Map<AmlSummaryRawData.StrStatus, Long> strCountByStatus;
        private final BigDecimal strFiledTotalAmount;
        private final BigDecimal filedRate;

        Computed(Map<AmlSummaryRawData.ActivityCategory, BigDecimal> thresholds,
                 Map<AmlSummaryRawData.ActivityCategory, Long> aboveThresholdCount,
                 Map<AmlSummaryRawData.ActivityCategory, BigDecimal> aboveThresholdTotal,
                 long allAboveThresholdCount,
                 BigDecimal allAboveThresholdTotal,
                 Map<AmlSummaryRawData.StrStatus, Long> strCountByStatus,
                 BigDecimal strFiledTotalAmount,
                 BigDecimal filedRate) {
            this.thresholds = thresholds;
            this.aboveThresholdCount = aboveThresholdCount;
            this.aboveThresholdTotal = aboveThresholdTotal;
            this.allAboveThresholdCount = allAboveThresholdCount;
            this.allAboveThresholdTotal = allAboveThresholdTotal;
            this.strCountByStatus = strCountByStatus;
            this.strFiledTotalAmount = strFiledTotalAmount;
            this.filedRate = filedRate;
        }
    }
}

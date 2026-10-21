package com.medfund.finance.regulatory.aml;

import com.medfund.finance.regulatory.service.PerRegulatorShaper;
import com.medfund.finance.regulatory.service.RegulatoryReportData;
import com.medfund.shared.report.ReportKey;
import com.medfund.shared.report.regulatory.RegulatoryReportCurrency;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Shapes the AML/STR periodic summary for a ({@code tenantId}, period).
 * Zips {@link AmlSummaryRawDataProvider} + {@link AmlThresholdReader}
 * through {@link AmlSummaryCalculator} into a {@link RegulatoryReportData}
 * whose section keys line up with {@link AmlCellMap}.
 *
 * <p>Reporting currency follows the tenant's country per
 * {@link RegulatoryReportCurrency#countryNativeFor(ReportKey, String)}
 * — {@code ZWL} for ZW, {@code ZAR} for ZA, {@code USD} for US. Client
 * currency overrides are rejected 422 upstream by the shape-service.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AmlSummaryReportShaper implements PerRegulatorShaper {

    public static final String SECTION_META = "meta";
    public static final String SECTION_THRESHOLDS = "thresholds";
    public static final String SECTION_ACTIVITY = "activity";
    public static final String SECTION_STR = "str";
    public static final String SECTION_SUMMARY = "summary";

    private final AmlSummaryRawDataProvider rawDataProvider;
    private final AmlThresholdReader thresholdReader;
    private final AmlSummaryCalculator calculator;

    @Override
    public ReportKey supportedKey() {
        return ReportKey.AML_STR;
    }

    @Override
    public Mono<RegulatoryReportData> shape(UUID tenantId,
                                            LocalDate periodStart,
                                            LocalDate periodEnd,
                                            String tenantCountryCode) {
        String currency = RegulatoryReportCurrency.resolveOrThrow(
                ReportKey.AML_STR, tenantCountryCode);
        return Mono.zip(
                        rawDataProvider.load(tenantId, periodStart, periodEnd),
                        thresholdReader.resolve(tenantId, tenantCountryCode, currency, periodEnd))
                .map(t -> compose(t.getT1(), t.getT2(), tenantId,
                        tenantCountryCode, periodStart, periodEnd, currency));
    }

    /** Package-private compose seam — tests drive raw + thresholds directly. */
    RegulatoryReportData compose(AmlSummaryRawData raw,
                                 AmlThresholds thresholds,
                                 UUID tenantId,
                                 String countryCode,
                                 LocalDate periodStart,
                                 LocalDate periodEnd,
                                 String currency) {
        AmlSummaryCalculator.Computed c = calculator.compute(raw, thresholds);

        Map<AmlField, Object> flat = new EnumMap<>(AmlField.class);
        // Meta
        flat.put(AmlField.META_REPORTING_ENTITY_NAME, raw.reportingEntityName());
        flat.put(AmlField.META_REGULATOR_REFERENCE, raw.regulatorReference());
        flat.put(AmlField.META_COUNTRY, countryCode);
        flat.put(AmlField.META_REPORTING_CURRENCY, currency);
        flat.put(AmlField.META_PERIOD_START, periodStart);
        flat.put(AmlField.META_PERIOD_END, periodEnd);
        // Thresholds
        flat.put(AmlField.THRESHOLD_PREMIUM,          c.getThresholds().get(AmlSummaryRawData.ActivityCategory.PREMIUM));
        flat.put(AmlField.THRESHOLD_CLAIM_PAYOUT,     c.getThresholds().get(AmlSummaryRawData.ActivityCategory.CLAIM_PAYOUT));
        flat.put(AmlField.THRESHOLD_ADVANCE_PAYMENT,  c.getThresholds().get(AmlSummaryRawData.ActivityCategory.ADVANCE_PAYMENT));
        flat.put(AmlField.THRESHOLD_COMMISSION,       c.getThresholds().get(AmlSummaryRawData.ActivityCategory.COMMISSION));
        flat.put(AmlField.THRESHOLD_OTHER,            c.getThresholds().get(AmlSummaryRawData.ActivityCategory.OTHER));
        // Activity
        flat.put(AmlField.ACTIVITY_PREMIUM_COUNT,            c.getAboveThresholdCount().get(AmlSummaryRawData.ActivityCategory.PREMIUM));
        flat.put(AmlField.ACTIVITY_PREMIUM_TOTAL,            c.getAboveThresholdTotal().get(AmlSummaryRawData.ActivityCategory.PREMIUM));
        flat.put(AmlField.ACTIVITY_CLAIM_PAYOUT_COUNT,       c.getAboveThresholdCount().get(AmlSummaryRawData.ActivityCategory.CLAIM_PAYOUT));
        flat.put(AmlField.ACTIVITY_CLAIM_PAYOUT_TOTAL,       c.getAboveThresholdTotal().get(AmlSummaryRawData.ActivityCategory.CLAIM_PAYOUT));
        flat.put(AmlField.ACTIVITY_ADVANCE_PAYMENT_COUNT,    c.getAboveThresholdCount().get(AmlSummaryRawData.ActivityCategory.ADVANCE_PAYMENT));
        flat.put(AmlField.ACTIVITY_ADVANCE_PAYMENT_TOTAL,    c.getAboveThresholdTotal().get(AmlSummaryRawData.ActivityCategory.ADVANCE_PAYMENT));
        flat.put(AmlField.ACTIVITY_COMMISSION_COUNT,         c.getAboveThresholdCount().get(AmlSummaryRawData.ActivityCategory.COMMISSION));
        flat.put(AmlField.ACTIVITY_COMMISSION_TOTAL,         c.getAboveThresholdTotal().get(AmlSummaryRawData.ActivityCategory.COMMISSION));
        flat.put(AmlField.ACTIVITY_OTHER_COUNT,              c.getAboveThresholdCount().get(AmlSummaryRawData.ActivityCategory.OTHER));
        flat.put(AmlField.ACTIVITY_OTHER_TOTAL,              c.getAboveThresholdTotal().get(AmlSummaryRawData.ActivityCategory.OTHER));
        flat.put(AmlField.ACTIVITY_ALL_ABOVE_THRESHOLD_COUNT, c.getAllAboveThresholdCount());
        flat.put(AmlField.ACTIVITY_ALL_ABOVE_THRESHOLD_TOTAL, c.getAllAboveThresholdTotal());
        // STR
        flat.put(AmlField.STR_RAISED_COUNT,   c.getStrCountByStatus().get(AmlSummaryRawData.StrStatus.RAISED));
        flat.put(AmlField.STR_REVIEWED_COUNT, c.getStrCountByStatus().get(AmlSummaryRawData.StrStatus.REVIEWED));
        flat.put(AmlField.STR_FILED_COUNT,    c.getStrCountByStatus().get(AmlSummaryRawData.StrStatus.FILED));
        flat.put(AmlField.STR_CLOSED_COUNT,   c.getStrCountByStatus().get(AmlSummaryRawData.StrStatus.CLOSED));
        flat.put(AmlField.STR_FILED_TOTAL_AMOUNT, c.getStrFiledTotalAmount());
        // Summary
        flat.put(AmlField.SUMMARY_FILED_RATE, c.getFiledRate());

        return RegulatoryReportData.builder(
                        ReportKey.AML_STR, tenantId, periodStart, periodEnd, currency)
                .section(SECTION_META, subMap(flat,
                        AmlField.META_REPORTING_ENTITY_NAME, AmlField.META_REGULATOR_REFERENCE,
                        AmlField.META_COUNTRY, AmlField.META_REPORTING_CURRENCY,
                        AmlField.META_PERIOD_START, AmlField.META_PERIOD_END))
                .section(SECTION_THRESHOLDS, subMap(flat,
                        AmlField.THRESHOLD_PREMIUM, AmlField.THRESHOLD_CLAIM_PAYOUT,
                        AmlField.THRESHOLD_ADVANCE_PAYMENT, AmlField.THRESHOLD_COMMISSION,
                        AmlField.THRESHOLD_OTHER))
                .section(SECTION_ACTIVITY, subMap(flat,
                        AmlField.ACTIVITY_PREMIUM_COUNT, AmlField.ACTIVITY_PREMIUM_TOTAL,
                        AmlField.ACTIVITY_CLAIM_PAYOUT_COUNT, AmlField.ACTIVITY_CLAIM_PAYOUT_TOTAL,
                        AmlField.ACTIVITY_ADVANCE_PAYMENT_COUNT, AmlField.ACTIVITY_ADVANCE_PAYMENT_TOTAL,
                        AmlField.ACTIVITY_COMMISSION_COUNT, AmlField.ACTIVITY_COMMISSION_TOTAL,
                        AmlField.ACTIVITY_OTHER_COUNT, AmlField.ACTIVITY_OTHER_TOTAL,
                        AmlField.ACTIVITY_ALL_ABOVE_THRESHOLD_COUNT, AmlField.ACTIVITY_ALL_ABOVE_THRESHOLD_TOTAL))
                .section(SECTION_STR, subMap(flat,
                        AmlField.STR_RAISED_COUNT, AmlField.STR_REVIEWED_COUNT,
                        AmlField.STR_FILED_COUNT, AmlField.STR_CLOSED_COUNT,
                        AmlField.STR_FILED_TOTAL_AMOUNT))
                .section(SECTION_SUMMARY, subMap(flat,
                        AmlField.SUMMARY_FILED_RATE))
                .build();
    }

    private static Map<String, Object> subMap(Map<AmlField, Object> flat, AmlField... keys) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (AmlField k : keys) {
            out.put(k.name(), flat.get(k));
        }
        return out;
    }

    public static Map<AmlField, Object> toCellValueMap(RegulatoryReportData data) {
        Map<AmlField, Object> out = new EnumMap<>(AmlField.class);
        for (Map<String, Object> section : data.sections().values()) {
            for (Map.Entry<String, Object> e : section.entrySet()) {
                try {
                    AmlField key = AmlField.valueOf(e.getKey());
                    out.put(key, e.getValue());
                } catch (IllegalArgumentException ignored) {
                    // Foreign entries tolerated.
                }
            }
        }
        return out;
    }
}

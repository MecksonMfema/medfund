package com.medfund.finance.regulatory.aml;

import com.medfund.finance.regulatory.service.RegulatoryReportData;
import com.medfund.shared.report.ReportKey;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AmlSummaryReportShaperTest {

    private static final UUID TENANT = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final LocalDate PERIOD_START = LocalDate.of(2026, 4, 1);
    private static final LocalDate PERIOD_END   = LocalDate.of(2026, 6, 30);

    private final AmlSummaryCalculator calculator = new AmlSummaryCalculator();
    private final AmlSummaryReportShaper shaper = new AmlSummaryReportShaper(
            (tenantId, ps, pe) -> Mono.just(goldenRaw()),
            (tenantId, country, currency, asOf) -> Mono.just(goldenThresholds()),
            calculator);

    @Test
    void supportedKey_isAmlStr() {
        assertThat(shaper.supportedKey()).isEqualTo(ReportKey.AML_STR);
    }

    @Test
    void shape_zaTenant_producesEveryExpectedSection_withZar() {
        StepVerifier.create(shaper.shape(TENANT, PERIOD_START, PERIOD_END, "ZA"))
                .assertNext(data -> {
                    assertThat(data.reportKey()).isEqualTo(ReportKey.AML_STR);
                    assertThat(data.reportingCurrency()).isEqualTo("ZAR");
                    assertThat(data.sections()).containsOnlyKeys(
                            AmlSummaryReportShaper.SECTION_META,
                            AmlSummaryReportShaper.SECTION_THRESHOLDS,
                            AmlSummaryReportShaper.SECTION_ACTIVITY,
                            AmlSummaryReportShaper.SECTION_STR,
                            AmlSummaryReportShaper.SECTION_SUMMARY);
                    assertThat(data.sections().get(AmlSummaryReportShaper.SECTION_META)
                            .get(AmlField.META_COUNTRY.name())).isEqualTo("ZA");
                })
                .verifyComplete();
    }

    @Test
    void shape_zwTenant_yieldsZwl() {
        StepVerifier.create(shaper.shape(TENANT, PERIOD_START, PERIOD_END, "ZW"))
                .assertNext(data -> {
                    assertThat(data.reportingCurrency()).isEqualTo("ZWL");
                    assertThat(data.sections().get(AmlSummaryReportShaper.SECTION_META)
                            .get(AmlField.META_COUNTRY.name())).isEqualTo("ZW");
                })
                .verifyComplete();
    }

    @Test
    void shape_usTenant_yieldsUsd() {
        StepVerifier.create(shaper.shape(TENANT, PERIOD_START, PERIOD_END, "US"))
                .assertNext(data -> {
                    assertThat(data.reportingCurrency()).isEqualTo("USD");
                    assertThat(data.sections().get(AmlSummaryReportShaper.SECTION_META)
                            .get(AmlField.META_COUNTRY.name())).isEqualTo("US");
                })
                .verifyComplete();
    }

    @Test
    void compose_populatesGoldenValues_exactly() {
        RegulatoryReportData data = shaper.compose(goldenRaw(), goldenThresholds(),
                TENANT, "ZA", PERIOD_START, PERIOD_END, "ZAR");

        Map<String, Object> activity = data.sections().get(AmlSummaryReportShaper.SECTION_ACTIVITY);
        assertThat((Long) activity.get(AmlField.ACTIVITY_ALL_ABOVE_THRESHOLD_COUNT.name())).isEqualTo(30L);
        assertThat((BigDecimal) activity.get(AmlField.ACTIVITY_ALL_ABOVE_THRESHOLD_TOTAL.name()))
                .isEqualByComparingTo("5230000.00");

        Map<String, Object> str = data.sections().get(AmlSummaryReportShaper.SECTION_STR);
        assertThat((Long) str.get(AmlField.STR_FILED_COUNT.name())).isEqualTo(6L);
        assertThat((BigDecimal) str.get(AmlField.STR_FILED_TOTAL_AMOUNT.name()))
                .isEqualByComparingTo("875000.00");

        Map<String, Object> summary = data.sections().get(AmlSummaryReportShaper.SECTION_SUMMARY);
        assertThat((BigDecimal) summary.get(AmlField.SUMMARY_FILED_RATE.name()))
                .isEqualByComparingTo("0.2000");
    }

    @Test
    void toCellValueMap_isRoundTripAgainstCompose() {
        RegulatoryReportData data = shaper.compose(goldenRaw(), goldenThresholds(),
                TENANT, "ZA", PERIOD_START, PERIOD_END, "ZAR");
        Map<AmlField, Object> flat = AmlSummaryReportShaper.toCellValueMap(data);

        assertThat(flat).containsKeys(AmlField.values());
        assertThat(flat.get(AmlField.META_REPORTING_CURRENCY)).isEqualTo("ZAR");
        assertThat((BigDecimal) flat.get(AmlField.SUMMARY_FILED_RATE))
                .isEqualByComparingTo("0.2000");
    }

    // ── Fixtures ─────────────────────────────────────────────────────

    static AmlSummaryRawData goldenRaw() {
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

        return new AmlSummaryRawData("Acme Insurance ZA (Pty) Ltd",
                "FIC-REG-2026-0000042",
                counts, totals, str, new BigDecimal("875000.00"));
    }

    static AmlThresholds goldenThresholds() {
        Map<AmlSummaryRawData.ActivityCategory, BigDecimal> byCategory =
                new EnumMap<>(AmlSummaryRawData.ActivityCategory.class);
        byCategory.put(AmlSummaryRawData.ActivityCategory.PREMIUM,         new BigDecimal("25000.00"));
        byCategory.put(AmlSummaryRawData.ActivityCategory.CLAIM_PAYOUT,    new BigDecimal("25000.00"));
        byCategory.put(AmlSummaryRawData.ActivityCategory.ADVANCE_PAYMENT, new BigDecimal("10000.00"));
        byCategory.put(AmlSummaryRawData.ActivityCategory.COMMISSION,      new BigDecimal("10000.00"));
        byCategory.put(AmlSummaryRawData.ActivityCategory.OTHER,           new BigDecimal("25000.00"));
        return new AmlThresholds(byCategory);
    }
}

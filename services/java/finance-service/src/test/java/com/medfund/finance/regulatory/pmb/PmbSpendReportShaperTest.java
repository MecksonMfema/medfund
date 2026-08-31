package com.medfund.finance.regulatory.pmb;

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

class PmbSpendReportShaperTest {

    private static final UUID TENANT = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final LocalDate PERIOD_START = LocalDate.of(2026, 1, 1);
    private static final LocalDate PERIOD_END   = LocalDate.of(2026, 12, 31);

    private final PmbSpendCalculator calculator = new PmbSpendCalculator();
    private final PmbSpendReportShaper shaper = new PmbSpendReportShaper(
            (tenantId, ps, pe) -> Mono.just(goldenRaw()),
            calculator);

    @Test
    void supportedKey_isPmbSpend() {
        assertThat(shaper.supportedKey()).isEqualTo(ReportKey.PMB_SPEND);
    }

    @Test
    void shape_producesEveryExpectedSection_withZarCurrency() {
        StepVerifier.create(shaper.shape(TENANT, PERIOD_START, PERIOD_END, "ZA"))
                .assertNext(data -> {
                    assertThat(data.reportKey()).isEqualTo(ReportKey.PMB_SPEND);
                    assertThat(data.reportingCurrency()).isEqualTo("ZAR");
                    assertThat(data.sections()).containsOnlyKeys(
                            PmbSpendReportShaper.SECTION_META,
                            PmbSpendReportShaper.SECTION_MEMBERSHIP,
                            PmbSpendReportShaper.SECTION_CATEGORY_PAID,
                            PmbSpendReportShaper.SECTION_CATEGORY_COUNT,
                            PmbSpendReportShaper.SECTION_TOTALS);
                })
                .verifyComplete();
    }

    @Test
    void compose_populatesGoldenValues_exactly() {
        RegulatoryReportData data = shaper.compose(goldenRaw(), TENANT,
                PERIOD_START, PERIOD_END, "ZAR");

        Map<String, Object> mem = data.sections().get(PmbSpendReportShaper.SECTION_MEMBERSHIP);
        assertThat(mem.get(PmbField.MEM_TOTAL_BENEFICIARIES.name())).isEqualTo(130000L);

        Map<String, Object> paid = data.sections().get(PmbSpendReportShaper.SECTION_CATEGORY_PAID);
        assertThat((BigDecimal) paid.get(PmbField.CAT_ONCOLOGY_PAID.name()))
                .isEqualByComparingTo("40000000.00");
        assertThat((BigDecimal) paid.get(PmbField.CAT_RESPIRATORY_PAID.name()))
                .isEqualByComparingTo("15000000.00");

        Map<String, Object> counts = data.sections().get(PmbSpendReportShaper.SECTION_CATEGORY_COUNT);
        assertThat(counts.get(PmbField.CAT_CARDIAC_COUNT.name())).isEqualTo(2500L);
        assertThat(counts.get(PmbField.CAT_OTHER_COUNT.name())).isEqualTo(350L);

        Map<String, Object> totals = data.sections().get(PmbSpendReportShaper.SECTION_TOTALS);
        assertThat((BigDecimal) totals.get(PmbField.TOTAL_PMB_PAID.name()))
                .isEqualByComparingTo("115000000.00");
        assertThat(totals.get(PmbField.TOTAL_PMB_COUNT.name())).isEqualTo(7500L);
        assertThat((BigDecimal) totals.get(PmbField.TOTAL_NON_PMB_PAID.name()))
                .isEqualByComparingTo("285000000.00");
        assertThat((BigDecimal) totals.get(PmbField.TOTAL_ALL_CLAIMS_PAID.name()))
                .isEqualByComparingTo("400000000.00");
        assertThat((BigDecimal) totals.get(PmbField.TOTAL_PMB_RATIO.name()))
                .isEqualByComparingTo("0.2875");
        assertThat((BigDecimal) totals.get(PmbField.TOTAL_PMB_PAID_PER_BENEFICIARY.name()))
                .isEqualByComparingTo("884.62");
    }

    @Test
    void toCellValueMap_isRoundTripAgainstCompose() {
        RegulatoryReportData data = shaper.compose(goldenRaw(), TENANT,
                PERIOD_START, PERIOD_END, "ZAR");

        Map<PmbField, Object> flat = PmbSpendReportShaper.toCellValueMap(data);

        assertThat(flat).containsKeys(PmbField.values());
        assertThat((BigDecimal) flat.get(PmbField.TOTAL_PMB_PAID))
                .isEqualByComparingTo("115000000.00");
        assertThat(flat.get(PmbField.META_REPORTING_CURRENCY)).isEqualTo("ZAR");
    }

    static PmbSpendRawData goldenRaw() {
        Map<PmbCategory, BigDecimal> paid = new EnumMap<>(PmbCategory.class);
        paid.put(PmbCategory.RESPIRATORY,   new BigDecimal("15000000.00"));
        paid.put(PmbCategory.CARDIAC,       new BigDecimal("25000000.00"));
        paid.put(PmbCategory.METABOLIC,     new BigDecimal("12000000.00"));
        paid.put(PmbCategory.ONCOLOGY,      new BigDecimal("40000000.00"));
        paid.put(PmbCategory.MENTAL_HEALTH, new BigDecimal("8000000.00"));
        paid.put(PmbCategory.RENAL,         new BigDecimal("10000000.00"));
        paid.put(PmbCategory.OTHER,         new BigDecimal("5000000.00"));
        Map<PmbCategory, Long> count = new EnumMap<>(PmbCategory.class);
        count.put(PmbCategory.RESPIRATORY,   1200L);
        count.put(PmbCategory.CARDIAC,       2500L);
        count.put(PmbCategory.METABOLIC,     1800L);
        count.put(PmbCategory.ONCOLOGY,       450L);
        count.put(PmbCategory.MENTAL_HEALTH,  900L);
        count.put(PmbCategory.RENAL,          300L);
        count.put(PmbCategory.OTHER,          350L);
        return new PmbSpendRawData(
                "Acme Medical Scheme ZA",
                "CMS-MS-000456",
                130000L,
                paid,
                count,
                new BigDecimal("285000000.00"));
    }
}

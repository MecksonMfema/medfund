package com.medfund.finance.regulatory.naic;

import com.medfund.finance.regulatory.service.RegulatoryParameterResolver;
import com.medfund.finance.regulatory.service.RegulatoryReportData;
import com.medfund.shared.report.ReportKey;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NaicSchedulePReportShaperTest {

    private static final UUID TENANT = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final LocalDate PERIOD_START = LocalDate.of(2026, 1, 1);
    private static final LocalDate PERIOD_END   = LocalDate.of(2026, 12, 31);

    private final NaicSchedulePCalculator calculator = new NaicSchedulePCalculator();
    private final NaicSchedulePReportShaper shaper = new NaicSchedulePReportShaper(
            (tenantId, ps, pe) -> Mono.just(goldenRaw()),
            calculator);

    @Test
    void supportedKey_isNaicScheduleP() {
        assertThat(shaper.supportedKey()).isEqualTo(ReportKey.NAIC_SCHEDULE_P);
    }

    @Test
    void shape_producesEveryExpectedSection_withUsdCurrency() {
        StepVerifier.create(shaper.shape(TENANT, PERIOD_START, PERIOD_END, "US"))
                .assertNext(data -> {
                    assertThat(data.reportKey()).isEqualTo(ReportKey.NAIC_SCHEDULE_P);
                    assertThat(data.reportingCurrency()).isEqualTo("USD");
                    assertThat(data.sections()).containsOnlyKeys(
                            NaicSchedulePReportShaper.SECTION_META,
                            NaicSchedulePReportShaper.SECTION_INCURRED,
                            NaicSchedulePReportShaper.SECTION_PAID,
                            NaicSchedulePReportShaper.SECTION_CASE,
                            NaicSchedulePReportShaper.SECTION_IBNR,
                            NaicSchedulePReportShaper.SECTION_EARNED_PREMIUM,
                            NaicSchedulePReportShaper.SECTION_LOSS_RATIOS,
                            NaicSchedulePReportShaper.SECTION_TOTALS);
                })
                .verifyComplete();
    }

    @Test
    void compose_populatesGoldenValues_exactly() {
        RegulatoryReportData data = shaper.compose(goldenRaw(), TENANT,
                PERIOD_START, PERIOD_END, "USD");

        Map<String, Object> incurred = data.sections().get(NaicSchedulePReportShaper.SECTION_INCURRED);
        assertThat((BigDecimal) incurred.get(NaicPField.P1_INCURRED_AY_MINUS_2.name()))
                .isEqualByComparingTo("125000000.00");
        assertThat((BigDecimal) incurred.get(NaicPField.P1_INCURRED_AY_MINUS_1.name()))
                .isEqualByComparingTo("100000000.00");
        assertThat((BigDecimal) incurred.get(NaicPField.P1_INCURRED_AY_CURRENT.name()))
                .isEqualByComparingTo("100000000.00");

        Map<String, Object> ratios = data.sections().get(NaicSchedulePReportShaper.SECTION_LOSS_RATIOS);
        assertThat((BigDecimal) ratios.get(NaicPField.P6_LOSS_RATIO_AY_MINUS_2.name()))
                .isEqualByComparingTo("0.6944");
        assertThat((BigDecimal) ratios.get(NaicPField.P6_LOSS_RATIO_AY_MINUS_1.name()))
                .isEqualByComparingTo("0.5263");
        assertThat((BigDecimal) ratios.get(NaicPField.P6_LOSS_RATIO_AY_CURRENT.name()))
                .isEqualByComparingTo("0.5000");

        Map<String, Object> totals = data.sections().get(NaicSchedulePReportShaper.SECTION_TOTALS);
        assertThat((BigDecimal) totals.get(NaicPField.TOTAL_INCURRED.name()))
                .isEqualByComparingTo("325000000.00");
        assertThat((BigDecimal) totals.get(NaicPField.TOTAL_EARNED_PREMIUM.name()))
                .isEqualByComparingTo("570000000.00");
        assertThat((BigDecimal) totals.get(NaicPField.OVERALL_LOSS_RATIO.name()))
                .isEqualByComparingTo("0.5702");
    }

    @Test
    void shape_resolverPath_consultsResolverForEveryKeyBeforeCompose() {
        // Phase 15b — Schedule P compute chain doesn't consume the params
        // yet (see the compose overload's javadoc), but the resolver is
        // still invoked so a broken YAML or rule surfaces on the shape
        // path rather than silently on a downstream ULAE-only export.
        RegulatoryParameterResolver resolver = mock(RegulatoryParameterResolver.class);
        when(resolver.resolve(eq(TENANT), eq("US_NAIC"), any(String.class), any(LocalDate.class)))
                .thenReturn(Mono.just(new BigDecimal("2.00")));
        NaicSchedulePReportShaper resolverShaper = new NaicSchedulePReportShaper(
                (t, ps, pe) -> Mono.just(goldenRaw()), calculator, resolver);

        StepVerifier.create(resolverShaper.shape(TENANT, PERIOD_START, PERIOD_END, "US"))
                .expectNextCount(1)
                .verifyComplete();

        // Every NaicSolvencyParameters key hit the resolver.
        verify(resolver, times(3)).resolve(eq(TENANT), eq("US_NAIC"),
                any(String.class), any(LocalDate.class));
    }

    @Test
    void toCellValueMap_isRoundTripAgainstCompose() {
        RegulatoryReportData data = shaper.compose(goldenRaw(), TENANT,
                PERIOD_START, PERIOD_END, "USD");

        Map<NaicPField, Object> flat = NaicSchedulePReportShaper.toCellValueMap(data);

        assertThat(flat).containsKeys(NaicPField.values());
        assertThat((BigDecimal) flat.get(NaicPField.OVERALL_LOSS_RATIO))
                .isEqualByComparingTo("0.5702");
        assertThat(flat.get(NaicPField.META_REPORTING_CURRENCY)).isEqualTo("USD");
    }

    static NaicSchedulePRawData goldenRaw() {
        return new NaicSchedulePRawData(
                "Acme Insurance US",
                "12345",
                "0999",
                "12-3456789",
                "IL",
                new BigDecimal("100000000.00"),
                new BigDecimal("60000000.00"),
                new BigDecimal("20000000.00"),
                new BigDecimal("20000000.00"),
                new BigDecimal("30000000.00"),
                new BigDecimal("50000000.00"),
                new BigDecimal("5000000.00"),
                new BigDecimal("10000000.00"),
                new BigDecimal("30000000.00"),
                new BigDecimal("180000000.00"),
                new BigDecimal("190000000.00"),
                new BigDecimal("200000000.00"));
    }
}

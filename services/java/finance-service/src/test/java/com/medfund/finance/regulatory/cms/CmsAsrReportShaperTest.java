package com.medfund.finance.regulatory.cms;

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
import static org.mockito.Mockito.when;

class CmsAsrReportShaperTest {

    private static final UUID TENANT = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final LocalDate PERIOD_START = LocalDate.of(2026, 1, 1);
    private static final LocalDate PERIOD_END   = LocalDate.of(2026, 12, 31);

    private final CmsAsrCalculator calculator = new CmsAsrCalculator();
    private final CmsAsrReportShaper shaper = new CmsAsrReportShaper(
            (tenantId, ps, pe) -> Mono.just(goldenRaw()),
            calculator);

    @Test
    void supportedKey_isCmsAsr() {
        assertThat(shaper.supportedKey()).isEqualTo(ReportKey.CMS_ASR);
    }

    @Test
    void shape_producesEveryExpectedSection_withZarCurrency() {
        StepVerifier.create(shaper.shape(TENANT, PERIOD_START, PERIOD_END, "ZA"))
                .assertNext(data -> {
                    assertThat(data.reportKey()).isEqualTo(ReportKey.CMS_ASR);
                    assertThat(data.reportingCurrency()).isEqualTo("ZAR");
                    assertThat(data.sections()).containsOnlyKeys(
                            CmsAsrReportShaper.SECTION_META,
                            CmsAsrReportShaper.SECTION_MEMBERSHIP,
                            CmsAsrReportShaper.SECTION_BALANCE_SHEET,
                            CmsAsrReportShaper.SECTION_INCOME_STATEMENT,
                            CmsAsrReportShaper.SECTION_COST_RATIOS,
                            CmsAsrReportShaper.SECTION_SOLVENCY);
                })
                .verifyComplete();
    }

    @Test
    void compose_populatesGoldenValues_exactly() {
        RegulatoryReportData data = shaper.compose(goldenRaw(), TENANT,
                PERIOD_START, PERIOD_END, "ZAR");

        Map<String, Object> mem = data.sections().get(CmsAsrReportShaper.SECTION_MEMBERSHIP);
        assertThat(mem.get(CmsField.MEM_PRINCIPAL_MEMBERS.name())).isEqualTo(50000L);
        assertThat(mem.get(CmsField.MEM_TOTAL_BENEFICIARIES.name())).isEqualTo(130000L);

        Map<String, Object> bs = data.sections().get(CmsAsrReportShaper.SECTION_BALANCE_SHEET);
        assertThat((BigDecimal) bs.get(CmsField.BS_TOTAL_ASSETS.name()))
                .isEqualByComparingTo("250000000.00");
        assertThat((BigDecimal) bs.get(CmsField.BS_ACCUMULATED_FUNDS.name()))
                .isEqualByComparingTo("150000000.00");

        Map<String, Object> inc = data.sections().get(CmsAsrReportShaper.SECTION_INCOME_STATEMENT);
        assertThat((BigDecimal) inc.get(CmsField.INC_NON_HEALTHCARE_TOTAL.name()))
                .isEqualByComparingTo("65000000.00");
        assertThat((BigDecimal) inc.get(CmsField.INC_NET_SURPLUS.name()))
                .isEqualByComparingTo("25000000.00");

        Map<String, Object> ratios = data.sections().get(CmsAsrReportShaper.SECTION_COST_RATIOS);
        assertThat((BigDecimal) ratios.get(CmsField.RATIO_CLAIMS.name()))
                .isEqualByComparingTo("0.8000");
        assertThat((BigDecimal) ratios.get(CmsField.RATIO_NON_HEALTHCARE.name()))
                .isEqualByComparingTo("0.1300");

        Map<String, Object> sol = data.sections().get(CmsAsrReportShaper.SECTION_SOLVENCY);
        assertThat((BigDecimal) sol.get(CmsField.SOL_ACCUMULATED_FUNDS.name()))
                .isEqualByComparingTo("150000000.00");
        assertThat((BigDecimal) sol.get(CmsField.SOL_MIN_REQUIRED_RESERVES.name()))
                .isEqualByComparingTo("125000000.00");
        assertThat((BigDecimal) sol.get(CmsField.SOL_ACTUAL_RATIO.name()))
                .isEqualByComparingTo("0.3000");
        assertThat((BigDecimal) sol.get(CmsField.SOL_SURPLUS_DEFICIT.name()))
                .isEqualByComparingTo("25000000.00");
    }

    @Test
    void shape_resolverOverride_flowsRuleValuesIntoSolvency() {
        // Phase 15b path — a tenant rule that tightens the min-solvency ratio
        // from 0.25 → 0.30 raises minRequired by 20% (500M × 0.05) and drops
        // the surplus by the same amount.
        RegulatoryParameterResolver resolver = mock(RegulatoryParameterResolver.class);
        when(resolver.resolve(eq(TENANT), eq("ZA_CMS_MEDICAL_SCHEME"),
                eq(CmsSolvencyParameters.KEY_MIN_SOLVENCY_RATIO), any(LocalDate.class)))
                .thenReturn(Mono.just(new BigDecimal("0.30")));
        when(resolver.resolve(eq(TENANT), eq("ZA_CMS_MEDICAL_SCHEME"),
                eq(CmsSolvencyParameters.KEY_NON_HEALTHCARE_COST_TARGET), any(LocalDate.class)))
                .thenReturn(Mono.just(new BigDecimal("0.10")));
        when(resolver.resolve(eq(TENANT), eq("ZA_CMS_MEDICAL_SCHEME"),
                eq(CmsSolvencyParameters.KEY_BROKER_FEES_CAP), any(LocalDate.class)))
                .thenReturn(Mono.just(new BigDecimal("0.03")));
        when(resolver.resolve(eq(TENANT), eq("ZA_CMS_MEDICAL_SCHEME"),
                eq(CmsSolvencyParameters.KEY_MANAGED_CARE_FEES_CAP), any(LocalDate.class)))
                .thenReturn(Mono.just(new BigDecimal("0.06")));
        CmsAsrReportShaper resolverShaper = new CmsAsrReportShaper(
                (t, ps, pe) -> Mono.just(goldenRaw()), calculator, resolver);

        StepVerifier.create(resolverShaper.shape(TENANT, PERIOD_START, PERIOD_END, "ZA"))
                .assertNext(data -> {
                    Map<String, Object> sol = data.sections().get(CmsAsrReportShaper.SECTION_SOLVENCY);
                    // grossContributions 500M × 0.30 = 150M required (vs 125M at 0.25).
                    assertThat((BigDecimal) sol.get(CmsField.SOL_MIN_REQUIRED_RESERVES.name()))
                            .isEqualByComparingTo("150000000.00");
                    // accumulatedFunds 150M − 150M required = 0 surplus.
                    assertThat((BigDecimal) sol.get(CmsField.SOL_SURPLUS_DEFICIT.name()))
                            .isEqualByComparingTo("0.00");
                })
                .verifyComplete();
    }

    @Test
    void toCellValueMap_isRoundTripAgainstCompose() {
        RegulatoryReportData data = shaper.compose(goldenRaw(), TENANT,
                PERIOD_START, PERIOD_END, "ZAR");

        Map<CmsField, Object> flat = CmsAsrReportShaper.toCellValueMap(data);

        assertThat(flat).containsKeys(CmsField.values());
        assertThat((BigDecimal) flat.get(CmsField.SOL_SURPLUS_DEFICIT))
                .isEqualByComparingTo("25000000.00");
        assertThat(flat.get(CmsField.META_REPORTING_CURRENCY)).isEqualTo("ZAR");
    }

    static CmsAsrRawData goldenRaw() {
        return new CmsAsrRawData(
                "Acme Medical Scheme ZA",
                "CMS-MS-000456",
                50000L,
                80000L,
                new BigDecimal("0.1500"),
                new BigDecimal("250000000.00"),
                new BigDecimal("100000000.00"),
                new BigDecimal("500000000.00"),
                new BigDecimal("490000000.00"),
                new BigDecimal("400000000.00"),
                new BigDecimal("40000000.00"),
                new BigDecimal("15000000.00"),
                new BigDecimal("10000000.00"));
    }
}

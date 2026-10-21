package com.medfund.finance.regulatory.ipec;

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

class IpecReportShaperTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final LocalDate PERIOD_START = LocalDate.of(2026, 4, 1);
    private static final LocalDate PERIOD_END   = LocalDate.of(2026, 6, 30);

    private final IpecSolvencyCalculator calculator = new IpecSolvencyCalculator();
    private final IpecReportShaper shaper = new IpecReportShaper(
            (tenantId, ps, pe) -> Mono.just(goldenRaw()),
            calculator);

    @Test
    void supportedKey_isIpecQuarterlyReturn() {
        assertThat(shaper.supportedKey()).isEqualTo(ReportKey.IPEC_QUARTERLY_RETURN);
    }

    @Test
    void shape_producesEveryExpectedSection_withZwlCurrency() {
        StepVerifier.create(shaper.shape(TENANT, PERIOD_START, PERIOD_END, "ZW"))
                .assertNext(data -> {
                    assertThat(data.reportKey()).isEqualTo(ReportKey.IPEC_QUARTERLY_RETURN);
                    assertThat(data.reportingCurrency()).isEqualTo("ZWL");
                    assertThat(data.sections()).containsOnlyKeys(
                            IpecReportShaper.SECTION_META,
                            IpecReportShaper.SECTION_BALANCE_SHEET,
                            IpecReportShaper.SECTION_REVENUE_ACCOUNT,
                            IpecReportShaper.SECTION_UPR,
                            IpecReportShaper.SECTION_OSC,
                            IpecReportShaper.SECTION_IBNR,
                            IpecReportShaper.SECTION_REINSURANCE,
                            IpecReportShaper.SECTION_SOLVENCY);
                })
                .verifyComplete();
    }

    @Test
    void compose_populatesGoldenValues_exactly() {
        RegulatoryReportData data = shaper.compose(goldenRaw(), TENANT,
                PERIOD_START, PERIOD_END, "ZWL");

        Map<String, Object> bs = data.sections().get(IpecReportShaper.SECTION_BALANCE_SHEET);
        assertThat((BigDecimal) bs.get(IpecField.BS_TOTAL_ASSETS.name()))
                .isEqualByComparingTo("12500000.00");
        assertThat((BigDecimal) bs.get(IpecField.BS_TOTAL_EQUITY.name()))
                .isEqualByComparingTo("4200000.00");

        Map<String, Object> rev = data.sections().get(IpecReportShaper.SECTION_REVENUE_ACCOUNT);
        assertThat((BigDecimal) rev.get(IpecField.REV_UNDERWRITING_RESULT.name()))
                .isEqualByComparingTo("800000.00");

        Map<String, Object> sol = data.sections().get(IpecReportShaper.SECTION_SOLVENCY);
        assertThat((BigDecimal) sol.get(IpecField.SOL_ADMITTED_CAPITAL.name()))
                .isEqualByComparingTo("4200000.00");
        assertThat((BigDecimal) sol.get(IpecField.SOL_MIN_REQUIRED_CAPITAL.name()))
                .isEqualByComparingTo("1590900.00");
        assertThat((BigDecimal) sol.get(IpecField.SOL_MARGIN.name()))
                .isEqualByComparingTo("2609100.00");
        assertThat((BigDecimal) sol.get(IpecField.SOL_RATIO.name()))
                .isEqualByComparingTo("2.6400");
    }

    @Test
    void shape_resolverOverride_flowsRuleValuesIntoSolvency() {
        // Phase 15b path — a tenant rule that raises the min-required-capital
        // multiplier from 0.30 → 0.60 flows through the resolver, doubles
        // minRequired, and drops the solvency margin exactly the same way
        // a manual param change would.
        RegulatoryParameterResolver resolver = mock(RegulatoryParameterResolver.class);
        when(resolver.resolve(eq(TENANT), eq("ZW_IPEC_SHORT_TERM"),
                eq(IpecSolvencyParameters.KEY_MIN_SOLVENCY_RATIO), any(LocalDate.class)))
                .thenReturn(Mono.just(new BigDecimal("1.30")));
        when(resolver.resolve(eq(TENANT), eq("ZW_IPEC_SHORT_TERM"),
                eq(IpecSolvencyParameters.KEY_MIN_REQUIRED_CAPITAL_MULTIPLIER), any(LocalDate.class)))
                .thenReturn(Mono.just(new BigDecimal("0.60")));
        when(resolver.resolve(eq(TENANT), eq("ZW_IPEC_SHORT_TERM"),
                eq(IpecSolvencyParameters.KEY_RESERVE_HAIRCUT_PCT), any(LocalDate.class)))
                .thenReturn(Mono.just(new BigDecimal("0.10")));
        when(resolver.resolve(eq(TENANT), eq("ZW_IPEC_SHORT_TERM"),
                eq(IpecSolvencyParameters.KEY_REINSURANCE_RECOVERABLE_PCT_HAIRCUT), any(LocalDate.class)))
                .thenReturn(Mono.just(new BigDecimal("0.05")));
        IpecReportShaper resolverShaper = new IpecReportShaper(
                (t, ps, pe) -> Mono.just(goldenRaw()), calculator, resolver);

        StepVerifier.create(resolverShaper.shape(TENANT, PERIOD_START, PERIOD_END, "ZW"))
                .assertNext(data -> {
                    Map<String, Object> sol = data.sections().get(IpecReportShaper.SECTION_SOLVENCY);
                    // 0.60 vs 0.30 doubles minRequired: 1,590,900 → 3,181,800.
                    assertThat((BigDecimal) sol.get(IpecField.SOL_MIN_REQUIRED_CAPITAL.name()))
                            .isEqualByComparingTo("3181800.00");
                    // Margin = admittedCapital (4,200,000) − minRequired (3,181,800) = 1,018,200.
                    assertThat((BigDecimal) sol.get(IpecField.SOL_MARGIN.name()))
                            .isEqualByComparingTo("1018200.00");
                })
                .verifyComplete();
    }

    @Test
    void toCellValueMap_isRoundTripAgainstCompose() {
        RegulatoryReportData data = shaper.compose(goldenRaw(), TENANT,
                PERIOD_START, PERIOD_END, "ZWL");

        Map<IpecField, Object> flat = IpecReportShaper.toCellValueMap(data);

        assertThat(flat).containsKeys(IpecField.values());
        assertThat((BigDecimal) flat.get(IpecField.SOL_MARGIN)).isEqualByComparingTo("2609100.00");
        assertThat(flat.get(IpecField.META_REPORTING_CURRENCY)).isEqualTo("ZWL");
    }

    private static IpecRawData goldenRaw() {
        return new IpecRawData(
                "Acme Insurance ZW",
                "IPEC-STI-000123",
                new BigDecimal("12500000.00"),
                new BigDecimal("8300000.00"),
                new BigDecimal("3000000.00"),
                new BigDecimal("1500000.00"),
                new BigDecimal("500000.00"),
                new BigDecimal("1000000.00"),
                new BigDecimal("3800000.00"),
                new BigDecimal("2400000.00"),
                new BigDecimal("600000.00"),
                new BigDecimal("800000.00"),
                new BigDecimal("400000.00"),
                new BigDecimal("150000.00"),
                new BigDecimal("900000.00"),
                new BigDecimal("300000.00"),
                new BigDecimal("100000.00"),
                new BigDecimal("250000.00"),
                new BigDecimal("80000.00"),
                new BigDecimal("40000.00"),
                new BigDecimal("200000.00"),
                new BigDecimal("90000.00"));
    }
}

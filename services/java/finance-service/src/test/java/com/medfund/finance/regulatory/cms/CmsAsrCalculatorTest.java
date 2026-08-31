package com.medfund.finance.regulatory.cms;

import com.medfund.finance.regulatory.service.RegulatoryParameterResolver;
import com.medfund.shared.report.regulatory.RegulatoryReportGenerationException;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CmsAsrCalculatorTest {

    private final CmsAsrCalculator calculator = new CmsAsrCalculator();

    // ── YAML load ───────────────────────────────────────────────────────────

    @Test
    void resolveParameters_loadsBundled_2024_06_01_baseline() {
        CmsSolvencyParameters params = calculator.resolveParameters(LocalDate.of(2026, 12, 31));

        assertThat(params.minSolvencyRatio()).isEqualByComparingTo("0.25");
        assertThat(params.nonHealthcareCostTarget()).isEqualByComparingTo("0.10");
        assertThat(params.brokerFeesCap()).isEqualByComparingTo("0.03");
        assertThat(params.managedCareFeesCap()).isEqualByComparingTo("0.06");
    }

    @Test
    void resolveParameters_failsLoudWhenNoYamlMatchesEffectiveDate() {
        // Effective 2020-01-01 is before the 2024-06-01 baseline → nothing matches.
        assertThatThrownBy(() -> calculator.resolveParameters(LocalDate.of(2020, 1, 1)))
                .isInstanceOf(RegulatoryReportGenerationException.class)
                .hasMessageContaining("No CMS solvency defaults YAML found");
    }

    // ── Version pick ────────────────────────────────────────────────────────

    @Test
    void pickHighestVersion_prefersLatestNotAfterEffectiveDate() {
        List<String> filenames = List.of("2024-06-01.yaml", "2025-01-01.yaml", "2027-01-01.yaml");
        Optional<String> pick = CmsAsrCalculator.pickHighestVersion(
                filenames, LocalDate.of(2026, 12, 31));

        assertThat(pick).isPresent()
                .get().asString().endsWith("2025-01-01.yaml");
    }

    @Test
    void pickHighestVersion_returnsEmpty_whenAllFilenamesAreAfterEffectiveDate() {
        Optional<String> pick = CmsAsrCalculator.pickHighestVersion(
                List.of("2027-01-01.yaml"), LocalDate.of(2026, 12, 31));

        assertThat(pick).isEmpty();
    }

    @Test
    void pickHighestVersion_skipsMalformedFilenames() {
        Optional<String> pick = CmsAsrCalculator.pickHighestVersion(
                List.of("baseline.yaml", "2024-06-01.yaml"), LocalDate.of(2026, 12, 31));

        assertThat(pick).isPresent()
                .get().asString().endsWith("2024-06-01.yaml");
    }

    // ── Solvency compute ────────────────────────────────────────────────────

    @Test
    void computeSolvency_fixtureMatchesGoldenValues() {
        // Golden fixture values (Phase 11 cms-asr-golden.yaml).
        CmsSolvencyParameters params = new CmsSolvencyParameters(
                new BigDecimal("0.25"),
                new BigDecimal("0.10"),
                new BigDecimal("0.03"),
                new BigDecimal("0.06"));

        CmsAsrCalculator.SolvencyResult r = calculator.computeSolvency(
                new BigDecimal("250000000.00"),      // totalAssets
                new BigDecimal("100000000.00"),      // totalLiabilities
                new BigDecimal("500000000.00"),      // grossContributions
                params);

        assertThat(r.accumulatedFunds()).isEqualByComparingTo("150000000.00");
        assertThat(r.minRequiredReserves()).isEqualByComparingTo("125000000.00");
        assertThat(r.actualRatio()).isEqualByComparingTo("0.3000");
        assertThat(r.minRequiredRatio()).isEqualByComparingTo("0.2500");
        assertThat(r.surplusDeficit()).isEqualByComparingTo("25000000.00");
        assertThat(r.meetsMinimum()).isTrue();
    }

    @Test
    void computeSolvency_failsMinimum_whenRatioBelow_25pct() {
        CmsSolvencyParameters params = new CmsSolvencyParameters(
                new BigDecimal("0.25"),
                new BigDecimal("0.10"),
                new BigDecimal("0.03"),
                new BigDecimal("0.06"));

        // Deliberately-tight accumulated funds (150M against 400M contribs → 37.5%),
        // then squeezed further to trip below 25%.
        CmsAsrCalculator.SolvencyResult r = calculator.computeSolvency(
                new BigDecimal("200000000.00"),      // totalAssets
                new BigDecimal("100000000.00"),      // totalLiabilities  → accumulated=100M
                new BigDecimal("500000000.00"),      // grossContributions
                params);

        assertThat(r.accumulatedFunds()).isEqualByComparingTo("100000000.00");
        assertThat(r.actualRatio()).isEqualByComparingTo("0.2000");   // 100M/500M
        assertThat(r.meetsMinimum()).isFalse();
    }

    @Test
    void computeSolvency_zeroGrossContributions_yieldsZeroRatio_andFailsMinimum() {
        CmsSolvencyParameters params = new CmsSolvencyParameters(
                new BigDecimal("0.25"), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE);

        CmsAsrCalculator.SolvencyResult r = calculator.computeSolvency(
                new BigDecimal("100.00"),
                new BigDecimal("50.00"),
                BigDecimal.ZERO,
                params);

        assertThat(r.actualRatio()).isEqualByComparingTo("0");
        assertThat(r.meetsMinimum()).isFalse();
    }

    @Test
    void computeSolvency_rejectsNullMonetaryInputs_withRegulatoryException() {
        CmsSolvencyParameters params = new CmsSolvencyParameters(
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE);

        assertThatThrownBy(() -> calculator.computeSolvency(
                null, BigDecimal.ZERO, BigDecimal.ZERO, params))
                .isInstanceOf(RegulatoryReportGenerationException.class)
                .hasMessageContaining("totalAssets");
    }

    @Test
    void computeSolvency_rejectsNullParams_withRegulatoryException() {
        assertThatThrownBy(() -> calculator.computeSolvency(
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, null))
                .isInstanceOf(RegulatoryReportGenerationException.class)
                .hasMessageContaining("solvency parameters required");
    }

    // ── Cost ratios ─────────────────────────────────────────────────────────

    @Test
    void computeCostRatios_fixtureMatchesGoldenValues() {
        CmsAsrCalculator.CostRatios r = calculator.computeCostRatios(
                new BigDecimal("500000000.00"),   // grossContributions
                new BigDecimal("400000000.00"),   // riskClaimsIncurred
                new BigDecimal("40000000.00"),    // adminExpenses
                new BigDecimal("15000000.00"),    // brokerFees
                new BigDecimal("10000000.00"));   // managedCareFees

        assertThat(r.claimsRatio()).isEqualByComparingTo("0.8000");
        assertThat(r.nonHealthcareRatio()).isEqualByComparingTo("0.1300");
        assertThat(r.adminRatio()).isEqualByComparingTo("0.0800");
        assertThat(r.brokerRatio()).isEqualByComparingTo("0.0300");
        assertThat(r.managedCareRatio()).isEqualByComparingTo("0.0200");
    }

    @Test
    void computeCostRatios_zeroGrossContributions_yieldsAllZeros_ratherThanDivideByZero() {
        CmsAsrCalculator.CostRatios r = calculator.computeCostRatios(
                BigDecimal.ZERO,
                new BigDecimal("100.00"),
                new BigDecimal("50.00"),
                new BigDecimal("25.00"),
                new BigDecimal("10.00"));

        assertThat(r.claimsRatio()).isEqualByComparingTo("0");
        assertThat(r.nonHealthcareRatio()).isEqualByComparingTo("0");
        assertThat(r.adminRatio()).isEqualByComparingTo("0");
        assertThat(r.brokerRatio()).isEqualByComparingTo("0");
        assertThat(r.managedCareRatio()).isEqualByComparingTo("0");
    }

    // ── Phase 15b rules-engine override overload ────────────────────────────

    @Test
    void resolveParameters_resolverOverload_composesEveryKeyThroughResolver() {
        RegulatoryParameterResolver resolver = mock(RegulatoryParameterResolver.class);
        UUID tenantId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        LocalDate effective = LocalDate.of(2026, 12, 31);
        Map<String, BigDecimal> resolved = Map.of(
                CmsSolvencyParameters.KEY_MIN_SOLVENCY_RATIO, new BigDecimal("0.30"),
                CmsSolvencyParameters.KEY_NON_HEALTHCARE_COST_TARGET, new BigDecimal("0.12"),
                CmsSolvencyParameters.KEY_BROKER_FEES_CAP, new BigDecimal("0.04"),
                CmsSolvencyParameters.KEY_MANAGED_CARE_FEES_CAP, new BigDecimal("0.07"));
        resolved.forEach((k, v) -> when(resolver.resolve(eq(tenantId), eq("ZA_CMS_MEDICAL_SCHEME"),
                eq(k), any(LocalDate.class))).thenReturn(Mono.just(v)));

        StepVerifier.create(calculator.resolveParameters(resolver, tenantId, effective))
                .assertNext(params -> {
                    assertThat(params.minSolvencyRatio()).isEqualByComparingTo("0.30");
                    assertThat(params.nonHealthcareCostTarget()).isEqualByComparingTo("0.12");
                    assertThat(params.brokerFeesCap()).isEqualByComparingTo("0.04");
                    assertThat(params.managedCareFeesCap()).isEqualByComparingTo("0.07");
                })
                .verifyComplete();
    }

    @Test
    void resolveParameters_resolverOverload_nullResolver_fallsBackToYaml() {
        StepVerifier.create(calculator.resolveParameters(null,
                        UUID.randomUUID(), LocalDate.of(2026, 12, 31)))
                .assertNext(params -> {
                    assertThat(params.minSolvencyRatio()).isEqualByComparingTo("0.25");
                    assertThat(params.nonHealthcareCostTarget()).isEqualByComparingTo("0.10");
                })
                .verifyComplete();
    }
}

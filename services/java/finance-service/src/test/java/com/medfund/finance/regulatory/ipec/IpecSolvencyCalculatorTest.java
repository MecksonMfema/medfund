package com.medfund.finance.regulatory.ipec;

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

class IpecSolvencyCalculatorTest {

    private final IpecSolvencyCalculator calculator = new IpecSolvencyCalculator();

    // ── YAML load ───────────────────────────────────────────────────────────

    @Test
    void resolveParameters_loadsBundled_2024_06_01_baseline() {
        IpecSolvencyParameters params = calculator.resolveParameters(LocalDate.of(2026, 6, 30));

        assertThat(params.minSolvencyRatio()).isEqualByComparingTo("1.30");
        assertThat(params.minRequiredCapitalMultiplier()).isEqualByComparingTo("0.30");
        assertThat(params.reserveHaircutPct()).isEqualByComparingTo("0.10");
        assertThat(params.reinsuranceRecoverableHaircutPct()).isEqualByComparingTo("0.05");
    }

    @Test
    void resolveParameters_failsLoudWhenNoYamlMatchesEffectiveDate() {
        // Effective 2020-01-01 is before the 2024-06-01 baseline → nothing matches.
        assertThatThrownBy(() -> calculator.resolveParameters(LocalDate.of(2020, 1, 1)))
                .isInstanceOf(RegulatoryReportGenerationException.class)
                .hasMessageContaining("No IPEC solvency defaults YAML found");
    }

    // ── Version pick ────────────────────────────────────────────────────────

    @Test
    void pickHighestVersion_prefersLatestNotAfterEffectiveDate() {
        List<String> filenames = List.of("2024-06-01.yaml", "2025-01-01.yaml", "2027-01-01.yaml");
        Optional<String> pick = IpecSolvencyCalculator.pickHighestVersion(
                filenames, LocalDate.of(2026, 6, 30));

        assertThat(pick).isPresent()
                .get().asString().endsWith("2025-01-01.yaml");
    }

    @Test
    void pickHighestVersion_returnsEmpty_whenAllFilenamesAreAfterEffectiveDate() {
        Optional<String> pick = IpecSolvencyCalculator.pickHighestVersion(
                List.of("2027-01-01.yaml"), LocalDate.of(2026, 6, 30));

        assertThat(pick).isEmpty();
    }

    @Test
    void pickHighestVersion_skipsMalformedFilenames() {
        Optional<String> pick = IpecSolvencyCalculator.pickHighestVersion(
                List.of("baseline.yaml", "2024-06-01.yaml"), LocalDate.of(2026, 6, 30));

        assertThat(pick).isPresent()
                .get().asString().endsWith("2024-06-01.yaml");
    }

    // ── Compute ─────────────────────────────────────────────────────────────

    @Test
    void compute_solvencyFixtureMatchesGoldenValues() {
        // Golden fixture values (Phase 10 ipec-quarterly-return-golden.yaml).
        IpecSolvencyParameters params = new IpecSolvencyParameters(
                new BigDecimal("1.30"),
                new BigDecimal("0.30"),
                new BigDecimal("0.10"),
                new BigDecimal("0.05"));

        IpecSolvencyCalculator.SolvencyResult r = calculator.compute(
                new BigDecimal("12500000.00"),      // totalAssets
                new BigDecimal("8300000.00"),       // totalLiabilities
                new BigDecimal("3800000.00"),       // netEarnedPremium
                new BigDecimal("1300000.00"),       // OSC total (900k + 300k + 100k)
                new BigDecimal("370000.00"),        // IBNR total (250k + 80k + 40k)
                params);

        assertThat(r.admittedCapital()).isEqualByComparingTo("4200000.00");
        assertThat(r.minRequiredCapital()).isEqualByComparingTo("1590900.00");
        assertThat(r.margin()).isEqualByComparingTo("2609100.00");
        assertThat(r.ratio()).isEqualByComparingTo("2.6400");
        assertThat(r.meetsMinimum()).isTrue();
    }

    @Test
    void compute_failsMinimum_whenRatioBelow_minSolvencyRatio() {
        IpecSolvencyParameters params = new IpecSolvencyParameters(
                new BigDecimal("1.30"),
                new BigDecimal("0.30"),
                new BigDecimal("0.10"),
                new BigDecimal("0.05"));

        // Deliberately-tight capital (net-liab is 500k against required = 1,590,900).
        IpecSolvencyCalculator.SolvencyResult r = calculator.compute(
                new BigDecimal("8800000.00"),       // totalAssets
                new BigDecimal("8300000.00"),       // totalLiabilities
                new BigDecimal("3800000.00"),
                new BigDecimal("1300000.00"),
                new BigDecimal("370000.00"),
                params);

        assertThat(r.admittedCapital()).isEqualByComparingTo("500000.00");
        assertThat(r.meetsMinimum()).isFalse();
    }

    @Test
    void compute_zeroMinRequired_yieldsZeroRatio_andFailsMinimum() {
        IpecSolvencyParameters params = new IpecSolvencyParameters(
                new BigDecimal("1.30"),
                new BigDecimal("0.30"),
                new BigDecimal("0.10"),
                new BigDecimal("0.05"));

        IpecSolvencyCalculator.SolvencyResult r = calculator.compute(
                new BigDecimal("100.00"),
                new BigDecimal("50.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                params);

        assertThat(r.minRequiredCapital()).isEqualByComparingTo("0.00");
        assertThat(r.ratio()).isEqualByComparingTo("0");
        assertThat(r.meetsMinimum()).isFalse();
    }

    @Test
    void compute_rejectsNullMonetaryInputs_withRegulatoryException() {
        IpecSolvencyParameters params = new IpecSolvencyParameters(
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO);

        assertThatThrownBy(() -> calculator.compute(
                null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, params))
                .isInstanceOf(RegulatoryReportGenerationException.class)
                .hasMessageContaining("totalAssets");
    }

    @Test
    void compute_rejectsNullParams_withRegulatoryException() {
        assertThatThrownBy(() -> calculator.compute(
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, null))
                .isInstanceOf(RegulatoryReportGenerationException.class)
                .hasMessageContaining("solvency parameters required");
    }

    // ── Phase 15 rules-engine override overload ─────────────────────────────

    @Test
    void resolveParameters_resolverOverload_composesEveryKeyThroughResolver() {
        // Every field goes through the resolver — a tenant rule wins, YAML is
        // the fallback (already covered in RegulatoryParameterResolverTest).
        RegulatoryParameterResolver resolver = mock(RegulatoryParameterResolver.class);
        UUID tenantId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        LocalDate effective = LocalDate.of(2026, 6, 30);
        Map<String, BigDecimal> resolved = Map.of(
                IpecSolvencyParameters.KEY_MIN_SOLVENCY_RATIO, new BigDecimal("1.45"),
                IpecSolvencyParameters.KEY_MIN_REQUIRED_CAPITAL_MULTIPLIER, new BigDecimal("0.35"),
                IpecSolvencyParameters.KEY_RESERVE_HAIRCUT_PCT, new BigDecimal("0.12"),
                IpecSolvencyParameters.KEY_REINSURANCE_RECOVERABLE_PCT_HAIRCUT, new BigDecimal("0.06"));
        resolved.forEach((k, v) -> when(resolver.resolve(eq(tenantId), eq("ZW_IPEC_SHORT_TERM"),
                eq(k), any(LocalDate.class))).thenReturn(Mono.just(v)));

        StepVerifier.create(calculator.resolveParameters(resolver, tenantId, effective))
                .assertNext(params -> {
                    assertThat(params.minSolvencyRatio()).isEqualByComparingTo("1.45");
                    assertThat(params.minRequiredCapitalMultiplier()).isEqualByComparingTo("0.35");
                    assertThat(params.reserveHaircutPct()).isEqualByComparingTo("0.12");
                    assertThat(params.reinsuranceRecoverableHaircutPct()).isEqualByComparingTo("0.06");
                })
                .verifyComplete();
    }

    @Test
    void resolveParameters_resolverOverload_nullResolver_fallsBackToYaml() {
        // A test that hasn't wired the resolver still gets the YAML defaults —
        // preserves backward-compat with shaper unit tests that don't inject
        // the resolver yet (Phase 15b retrofit).
        StepVerifier.create(calculator.resolveParameters(null,
                        UUID.randomUUID(), LocalDate.of(2026, 6, 30)))
                .assertNext(params -> {
                    assertThat(params.minSolvencyRatio()).isEqualByComparingTo("1.30");
                    assertThat(params.minRequiredCapitalMultiplier()).isEqualByComparingTo("0.30");
                })
                .verifyComplete();
    }
}

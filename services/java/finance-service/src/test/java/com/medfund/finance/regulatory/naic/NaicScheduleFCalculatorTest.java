package com.medfund.finance.regulatory.naic;

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

class NaicScheduleFCalculatorTest {

    private final NaicScheduleFCalculator calculator = new NaicScheduleFCalculator();

    // ── YAML load ───────────────────────────────────────────────────────────

    @Test
    void resolveParameters_loadsBundled_2024_06_01_baseline() {
        NaicScheduleFParameters params = calculator.resolveParameters(LocalDate.of(2026, 12, 31));

        assertThat(params.unauthorizedReinsurerProvisionPercentage()).isEqualByComparingTo("1.00");
        assertThat(params.certifiedReinsurerProvisionPercentage()).isEqualByComparingTo("0.20");
    }

    @Test
    void resolveParameters_failsLoudWhenNoYamlMatchesEffectiveDate() {
        assertThatThrownBy(() -> calculator.resolveParameters(LocalDate.of(2020, 1, 1)))
                .isInstanceOf(RegulatoryReportGenerationException.class)
                .hasMessageContaining("No NAIC Schedule F defaults YAML found");
    }

    // ── Version pick ────────────────────────────────────────────────────────

    @Test
    void pickHighestVersion_prefersLatestNotAfterEffectiveDate() {
        List<String> filenames = List.of("2024-06-01.yaml", "2025-01-01.yaml", "2027-01-01.yaml");
        Optional<String> pick = NaicScheduleFCalculator.pickHighestVersion(
                filenames, LocalDate.of(2026, 12, 31));

        assertThat(pick).isPresent()
                .get().asString().endsWith("2025-01-01.yaml");
    }

    @Test
    void pickHighestVersion_returnsEmpty_whenAllFilenamesAreAfterEffectiveDate() {
        Optional<String> pick = NaicScheduleFCalculator.pickHighestVersion(
                List.of("2027-01-01.yaml"), LocalDate.of(2026, 12, 31));

        assertThat(pick).isEmpty();
    }

    @Test
    void pickHighestVersion_skipsMalformedFilenames() {
        Optional<String> pick = NaicScheduleFCalculator.pickHighestVersion(
                List.of("baseline.yaml", "2024-06-01.yaml"), LocalDate.of(2026, 12, 31));

        assertThat(pick).isPresent()
                .get().asString().endsWith("2024-06-01.yaml");
    }

    // ── Stratum compute ─────────────────────────────────────────────────────

    @Test
    void computeStratum_fixtureMatchesGoldenValues_affiliated() {
        NaicScheduleFCalculator.StratumResult r = calculator.computeStratum(
                new BigDecimal("10000000.00"),   // premiums
                new BigDecimal("5000000.00"),    // losses paid
                new BigDecimal("3000000.00"));   // losses unpaid

        assertThat(r.premiums()).isEqualByComparingTo("10000000.00");
        assertThat(r.lossesPaid()).isEqualByComparingTo("5000000.00");
        assertThat(r.lossesUnpaid()).isEqualByComparingTo("3000000.00");
        assertThat(r.recoverable()).isEqualByComparingTo("8000000.00"); // 5M + 3M
    }

    @Test
    void computeStratum_fixtureMatchesGoldenValues_unauthorized() {
        NaicScheduleFCalculator.StratumResult r = calculator.computeStratum(
                new BigDecimal("5000000.00"),
                new BigDecimal("2000000.00"),
                new BigDecimal("1000000.00"));

        assertThat(r.recoverable()).isEqualByComparingTo("3000000.00");   // 2M + 1M
    }

    @Test
    void computeStratum_fixtureMatchesGoldenValues_certified() {
        NaicScheduleFCalculator.StratumResult r = calculator.computeStratum(
                new BigDecimal("8000000.00"),
                new BigDecimal("3000000.00"),
                new BigDecimal("2000000.00"));

        assertThat(r.recoverable()).isEqualByComparingTo("5000000.00");   // 3M + 2M
    }

    @Test
    void computeStratum_rejectsNullInputs_withRegulatoryException() {
        assertThatThrownBy(() -> calculator.computeStratum(
                null, BigDecimal.ZERO, BigDecimal.ZERO))
                .isInstanceOf(RegulatoryReportGenerationException.class)
                .hasMessageContaining("premiums");
        assertThatThrownBy(() -> calculator.computeStratum(
                BigDecimal.ZERO, null, BigDecimal.ZERO))
                .isInstanceOf(RegulatoryReportGenerationException.class)
                .hasMessageContaining("lossesPaid");
        assertThatThrownBy(() -> calculator.computeStratum(
                BigDecimal.ZERO, BigDecimal.ZERO, null))
                .isInstanceOf(RegulatoryReportGenerationException.class)
                .hasMessageContaining("lossesUnpaid");
    }

    // ── Totals compute ──────────────────────────────────────────────────────

    @Test
    void computeCededTotals_fixtureMatchesGoldenValues() {
        NaicScheduleFCalculator.StratumResult affiliated = calculator.computeStratum(
                new BigDecimal("10000000.00"), new BigDecimal("5000000.00"), new BigDecimal("3000000.00"));
        NaicScheduleFCalculator.StratumResult authorized = calculator.computeStratum(
                new BigDecimal("25000000.00"), new BigDecimal("12000000.00"), new BigDecimal("8000000.00"));
        NaicScheduleFCalculator.StratumResult unauthorized = calculator.computeStratum(
                new BigDecimal("5000000.00"), new BigDecimal("2000000.00"), new BigDecimal("1000000.00"));
        NaicScheduleFCalculator.StratumResult certified = calculator.computeStratum(
                new BigDecimal("8000000.00"), new BigDecimal("3000000.00"), new BigDecimal("2000000.00"));
        NaicScheduleFParameters parameters = new NaicScheduleFParameters(
                new BigDecimal("1.00"), new BigDecimal("0.20"));

        NaicScheduleFCalculator.CededTotalsResult t = calculator.computeCededTotals(
                affiliated, authorized, unauthorized, certified, parameters);

        assertThat(t.totalCededPremiums()).isEqualByComparingTo("48000000.00");        // 10 + 25 + 5 + 8
        assertThat(t.totalCededLossesPaid()).isEqualByComparingTo("22000000.00");      //  5 + 12 + 2 + 3
        assertThat(t.totalCededLossesUnpaid()).isEqualByComparingTo("14000000.00");    //  3 +  8 + 1 + 2
        assertThat(t.totalReinsuranceRecoverable()).isEqualByComparingTo("36000000.00"); // 22 + 14
        assertThat(t.provisionForReinsurance()).isEqualByComparingTo("4000000.00");    // 3×1.00 + 5×0.20
        assertThat(t.netReinsurancePosition()).isEqualByComparingTo("32000000.00");    // 36 - 4
    }

    @Test
    void computeCededTotals_zeroUnauthorizedAndCertified_yieldsZeroProvision() {
        NaicScheduleFCalculator.StratumResult zero = calculator.computeStratum(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        NaicScheduleFCalculator.StratumResult authorized = calculator.computeStratum(
                new BigDecimal("100.00"), new BigDecimal("50.00"), new BigDecimal("25.00"));
        NaicScheduleFParameters parameters = new NaicScheduleFParameters(
                new BigDecimal("1.00"), new BigDecimal("0.20"));

        NaicScheduleFCalculator.CededTotalsResult t = calculator.computeCededTotals(
                zero, authorized, zero, zero, parameters);

        assertThat(t.provisionForReinsurance()).isEqualByComparingTo("0");
        assertThat(t.netReinsurancePosition()).isEqualByComparingTo("75.00");   // 50 + 25 recoverable
    }

    @Test
    void computeCededTotals_partialProvision_certifiedOnly() {
        NaicScheduleFCalculator.StratumResult zero = calculator.computeStratum(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        NaicScheduleFCalculator.StratumResult certified = calculator.computeStratum(
                new BigDecimal("1000.00"), new BigDecimal("400.00"), new BigDecimal("100.00"));
        NaicScheduleFParameters parameters = new NaicScheduleFParameters(
                new BigDecimal("1.00"), new BigDecimal("0.20"));

        NaicScheduleFCalculator.CededTotalsResult t = calculator.computeCededTotals(
                zero, zero, zero, certified, parameters);

        // certified recoverable = 400 + 100 = 500; provision = 500 × 0.20 = 100
        assertThat(t.provisionForReinsurance()).isEqualByComparingTo("100.00");
        assertThat(t.netReinsurancePosition()).isEqualByComparingTo("400.00");   // 500 - 100
    }

    @Test
    void computeCededTotals_rejectsNullStratum_withRegulatoryException() {
        NaicScheduleFCalculator.StratumResult ok = calculator.computeStratum(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        NaicScheduleFParameters parameters = new NaicScheduleFParameters(
                new BigDecimal("1.00"), new BigDecimal("0.20"));

        assertThatThrownBy(() -> calculator.computeCededTotals(null, ok, ok, ok, parameters))
                .isInstanceOf(RegulatoryReportGenerationException.class)
                .hasMessageContaining("affiliated");
    }

    @Test
    void computeCededTotals_rejectsNullParameters_withRegulatoryException() {
        NaicScheduleFCalculator.StratumResult ok = calculator.computeStratum(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        assertThatThrownBy(() -> calculator.computeCededTotals(ok, ok, ok, ok, null))
                .isInstanceOf(RegulatoryReportGenerationException.class)
                .hasMessageContaining("parameters");
    }

    // ── Phase 15b rules-engine override overload ────────────────────────────

    @Test
    void resolveParameters_resolverOverload_composesEveryKeyThroughResolver() {
        RegulatoryParameterResolver resolver = mock(RegulatoryParameterResolver.class);
        UUID tenantId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        LocalDate effective = LocalDate.of(2026, 12, 31);
        Map<String, BigDecimal> resolved = Map.of(
                NaicScheduleFParameters.KEY_UNAUTHORIZED_PROVISION_PCT, new BigDecimal("0.90"),
                NaicScheduleFParameters.KEY_CERTIFIED_PROVISION_PCT, new BigDecimal("0.30"));
        resolved.forEach((k, v) -> when(resolver.resolve(eq(tenantId), eq("US_NAIC"),
                eq(k), any(LocalDate.class))).thenReturn(Mono.just(v)));

        StepVerifier.create(calculator.resolveParameters(resolver, tenantId, effective))
                .assertNext(params -> {
                    assertThat(params.unauthorizedReinsurerProvisionPercentage()).isEqualByComparingTo("0.90");
                    assertThat(params.certifiedReinsurerProvisionPercentage()).isEqualByComparingTo("0.30");
                })
                .verifyComplete();
    }

    @Test
    void resolveParameters_resolverOverload_nullResolver_fallsBackToYaml() {
        StepVerifier.create(calculator.resolveParameters(null,
                        UUID.randomUUID(), LocalDate.of(2026, 12, 31)))
                .assertNext(params -> {
                    assertThat(params.unauthorizedReinsurerProvisionPercentage()).isEqualByComparingTo("1.00");
                    assertThat(params.certifiedReinsurerProvisionPercentage()).isEqualByComparingTo("0.20");
                })
                .verifyComplete();
    }
}

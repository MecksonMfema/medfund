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

class NaicSchedulePCalculatorTest {

    private final NaicSchedulePCalculator calculator = new NaicSchedulePCalculator();

    // ── YAML load ───────────────────────────────────────────────────────────

    @Test
    void resolveParameters_loadsBundled_2024_06_01_baseline() {
        NaicSolvencyParameters params = calculator.resolveParameters(LocalDate.of(2026, 12, 31));

        assertThat(params.minRbcRatioCompanyActionLevel()).isEqualByComparingTo("2.00");
        assertThat(params.ulaeRatio()).isEqualByComparingTo("0.05");
        assertThat(params.lossRatioHighWatermark()).isEqualByComparingTo("1.00");
    }

    @Test
    void resolveParameters_failsLoudWhenNoYamlMatchesEffectiveDate() {
        assertThatThrownBy(() -> calculator.resolveParameters(LocalDate.of(2020, 1, 1)))
                .isInstanceOf(RegulatoryReportGenerationException.class)
                .hasMessageContaining("No NAIC solvency defaults YAML found");
    }

    // ── Version pick ────────────────────────────────────────────────────────

    @Test
    void pickHighestVersion_prefersLatestNotAfterEffectiveDate() {
        List<String> filenames = List.of("2024-06-01.yaml", "2025-01-01.yaml", "2027-01-01.yaml");
        Optional<String> pick = NaicSchedulePCalculator.pickHighestVersion(
                filenames, LocalDate.of(2026, 12, 31));

        assertThat(pick).isPresent()
                .get().asString().endsWith("2025-01-01.yaml");
    }

    @Test
    void pickHighestVersion_returnsEmpty_whenAllFilenamesAreAfterEffectiveDate() {
        Optional<String> pick = NaicSchedulePCalculator.pickHighestVersion(
                List.of("2027-01-01.yaml"), LocalDate.of(2026, 12, 31));

        assertThat(pick).isEmpty();
    }

    @Test
    void pickHighestVersion_skipsMalformedFilenames() {
        Optional<String> pick = NaicSchedulePCalculator.pickHighestVersion(
                List.of("baseline.yaml", "2024-06-01.yaml"), LocalDate.of(2026, 12, 31));

        assertThat(pick).isPresent()
                .get().asString().endsWith("2024-06-01.yaml");
    }

    // ── Accident-year compute ───────────────────────────────────────────────

    @Test
    void computeAccidentYear_fixtureMatchesGoldenValues_ayMinus2() {
        NaicSchedulePCalculator.AccidentYearResult r = calculator.computeAccidentYear(
                new BigDecimal("100000000.00"),   // paid
                new BigDecimal("20000000.00"),    // case
                new BigDecimal("5000000.00"),     // ibnr
                new BigDecimal("180000000.00")); // earned premium

        assertThat(r.incurred()).isEqualByComparingTo("125000000.00");
        assertThat(r.paid()).isEqualByComparingTo("100000000.00");
        assertThat(r.caseReserves()).isEqualByComparingTo("20000000.00");
        assertThat(r.ibnr()).isEqualByComparingTo("5000000.00");
        assertThat(r.earnedPremium()).isEqualByComparingTo("180000000.00");
        assertThat(r.lossRatio()).isEqualByComparingTo("0.6944");   // 125/180 HALF_UP
    }

    @Test
    void computeAccidentYear_fixtureMatchesGoldenValues_ayMinus1() {
        NaicSchedulePCalculator.AccidentYearResult r = calculator.computeAccidentYear(
                new BigDecimal("60000000.00"),
                new BigDecimal("30000000.00"),
                new BigDecimal("10000000.00"),
                new BigDecimal("190000000.00"));

        assertThat(r.incurred()).isEqualByComparingTo("100000000.00");
        assertThat(r.lossRatio()).isEqualByComparingTo("0.5263");   // 100/190 HALF_UP
    }

    @Test
    void computeAccidentYear_fixtureMatchesGoldenValues_ayCurrent() {
        NaicSchedulePCalculator.AccidentYearResult r = calculator.computeAccidentYear(
                new BigDecimal("20000000.00"),
                new BigDecimal("50000000.00"),
                new BigDecimal("30000000.00"),
                new BigDecimal("200000000.00"));

        assertThat(r.incurred()).isEqualByComparingTo("100000000.00");
        assertThat(r.lossRatio()).isEqualByComparingTo("0.5000");
    }

    @Test
    void computeAccidentYear_zeroEarnedPremium_yieldsZeroLossRatio() {
        NaicSchedulePCalculator.AccidentYearResult r = calculator.computeAccidentYear(
                new BigDecimal("100.00"),
                new BigDecimal("50.00"),
                new BigDecimal("25.00"),
                BigDecimal.ZERO);

        assertThat(r.incurred()).isEqualByComparingTo("175.00");
        assertThat(r.lossRatio()).isEqualByComparingTo("0");
    }

    @Test
    void computeAccidentYear_rejectsNullInputs_withRegulatoryException() {
        assertThatThrownBy(() -> calculator.computeAccidentYear(
                null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO))
                .isInstanceOf(RegulatoryReportGenerationException.class)
                .hasMessageContaining("paid");
    }

    // ── Totals ──────────────────────────────────────────────────────────────

    @Test
    void computeTotals_fixtureMatchesGoldenValues() {
        NaicSchedulePCalculator.AccidentYearResult ayMinus2 = calculator.computeAccidentYear(
                new BigDecimal("100000000.00"), new BigDecimal("20000000.00"),
                new BigDecimal("5000000.00"), new BigDecimal("180000000.00"));
        NaicSchedulePCalculator.AccidentYearResult ayMinus1 = calculator.computeAccidentYear(
                new BigDecimal("60000000.00"), new BigDecimal("30000000.00"),
                new BigDecimal("10000000.00"), new BigDecimal("190000000.00"));
        NaicSchedulePCalculator.AccidentYearResult ayCurrent = calculator.computeAccidentYear(
                new BigDecimal("20000000.00"), new BigDecimal("50000000.00"),
                new BigDecimal("30000000.00"), new BigDecimal("200000000.00"));

        NaicSchedulePCalculator.TotalsResult t = calculator.computeTotals(
                List.of(ayMinus2, ayMinus1, ayCurrent));

        assertThat(t.totalIncurred()).isEqualByComparingTo("325000000.00");
        assertThat(t.totalPaid()).isEqualByComparingTo("180000000.00");
        assertThat(t.totalCaseReserves()).isEqualByComparingTo("100000000.00");
        assertThat(t.totalIbnr()).isEqualByComparingTo("45000000.00");
        assertThat(t.totalEarnedPremium()).isEqualByComparingTo("570000000.00");
        assertThat(t.overallLossRatio()).isEqualByComparingTo("0.5702");   // 325/570 HALF_UP
    }

    @Test
    void computeTotals_zeroEarnedPremium_yieldsZeroOverallRatio() {
        NaicSchedulePCalculator.AccidentYearResult ay = calculator.computeAccidentYear(
                new BigDecimal("100.00"), new BigDecimal("50.00"), new BigDecimal("25.00"),
                BigDecimal.ZERO);

        NaicSchedulePCalculator.TotalsResult t = calculator.computeTotals(List.of(ay));

        assertThat(t.overallLossRatio()).isEqualByComparingTo("0");
    }

    @Test
    void computeTotals_rejectsEmptyList_withRegulatoryException() {
        assertThatThrownBy(() -> calculator.computeTotals(List.of()))
                .isInstanceOf(RegulatoryReportGenerationException.class)
                .hasMessageContaining("ayResults required");
    }

    // ── Phase 15b rules-engine override overload ────────────────────────────

    @Test
    void resolveParameters_resolverOverload_composesEveryKeyThroughResolver() {
        RegulatoryParameterResolver resolver = mock(RegulatoryParameterResolver.class);
        UUID tenantId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        LocalDate effective = LocalDate.of(2026, 12, 31);
        Map<String, BigDecimal> resolved = Map.of(
                NaicSolvencyParameters.KEY_MIN_RBC_RATIO_COMPANY_ACTION_LEVEL, new BigDecimal("2.50"),
                NaicSolvencyParameters.KEY_ULAE_RATIO, new BigDecimal("0.06"),
                NaicSolvencyParameters.KEY_LOSS_RATIO_HIGH_WATERMARK, new BigDecimal("1.10"));
        resolved.forEach((k, v) -> when(resolver.resolve(eq(tenantId), eq("US_NAIC"),
                eq(k), any(LocalDate.class))).thenReturn(Mono.just(v)));

        StepVerifier.create(calculator.resolveParameters(resolver, tenantId, effective))
                .assertNext(params -> {
                    assertThat(params.minRbcRatioCompanyActionLevel()).isEqualByComparingTo("2.50");
                    assertThat(params.ulaeRatio()).isEqualByComparingTo("0.06");
                    assertThat(params.lossRatioHighWatermark()).isEqualByComparingTo("1.10");
                })
                .verifyComplete();
    }

    @Test
    void resolveParameters_resolverOverload_nullResolver_fallsBackToYaml() {
        StepVerifier.create(calculator.resolveParameters(null,
                        UUID.randomUUID(), LocalDate.of(2026, 12, 31)))
                .assertNext(params -> {
                    assertThat(params.minRbcRatioCompanyActionLevel()).isEqualByComparingTo("2.00");
                    assertThat(params.ulaeRatio()).isEqualByComparingTo("0.05");
                })
                .verifyComplete();
    }
}

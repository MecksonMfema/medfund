package com.medfund.finance.regulatory.pmb;

import com.medfund.shared.report.regulatory.RegulatoryReportGenerationException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PmbSpendCalculatorTest {

    private final PmbSpendCalculator calculator = new PmbSpendCalculator();

    @Test
    void computeSummary_fixtureMatchesGoldenValues() {
        // Golden fixture values (Phase 18 pmb-spend-golden.yaml).
        PmbSpendCalculator.Summary s = calculator.computeSummary(
                new BigDecimal("115000000.00"),     // totalPmbPaid
                new BigDecimal("285000000.00"),     // totalNonPmbPaid
                130000L);                            // totalBeneficiaries

        assertThat(s.totalPmbPaid()).isEqualByComparingTo("115000000.00");
        assertThat(s.totalNonPmbPaid()).isEqualByComparingTo("285000000.00");
        assertThat(s.totalAllClaimsPaid()).isEqualByComparingTo("400000000.00");
        assertThat(s.pmbRatio()).isEqualByComparingTo("0.2875");
        assertThat(s.pmbPaidPerBeneficiary()).isEqualByComparingTo("884.62");
    }

    @Test
    void computeSummary_zeroTotalClaims_yieldsZeroRatio_ratherThanDivideByZero() {
        PmbSpendCalculator.Summary s = calculator.computeSummary(
                BigDecimal.ZERO, BigDecimal.ZERO, 1000L);

        assertThat(s.totalAllClaimsPaid()).isEqualByComparingTo("0.00");
        assertThat(s.pmbRatio()).isEqualByComparingTo("0");
        assertThat(s.pmbPaidPerBeneficiary()).isEqualByComparingTo("0.00");
    }

    @Test
    void computeSummary_zeroBeneficiaries_yieldsZeroPerBeneficiary() {
        PmbSpendCalculator.Summary s = calculator.computeSummary(
                new BigDecimal("100000.00"),
                new BigDecimal("400000.00"),
                0L);

        assertThat(s.pmbRatio()).isEqualByComparingTo("0.2000");
        assertThat(s.pmbPaidPerBeneficiary()).isEqualByComparingTo("0");
    }

    @Test
    void computeSummary_nullPmbPaid_rejectedWithRegulatoryException() {
        assertThatThrownBy(() -> calculator.computeSummary(null, BigDecimal.ZERO, 100L))
                .isInstanceOf(RegulatoryReportGenerationException.class)
                .hasMessageContaining("totalPmbPaid");
    }

    @Test
    void computeSummary_nullNonPmbPaid_rejectedWithRegulatoryException() {
        assertThatThrownBy(() -> calculator.computeSummary(BigDecimal.ZERO, null, 100L))
                .isInstanceOf(RegulatoryReportGenerationException.class)
                .hasMessageContaining("totalNonPmbPaid");
    }

    @Test
    void computeSummary_negativeBeneficiaries_rejectedWithRegulatoryException() {
        assertThatThrownBy(() -> calculator.computeSummary(
                BigDecimal.ZERO, BigDecimal.ZERO, -5L))
                .isInstanceOf(RegulatoryReportGenerationException.class)
                .hasMessageContaining("totalBeneficiaries");
    }

    @Test
    void computeSummary_ratioRoundsHalfUpTo4dp() {
        // 1/3 = 0.33333... → 4dp HALF_UP = 0.3333
        PmbSpendCalculator.Summary s = calculator.computeSummary(
                new BigDecimal("100.00"),
                new BigDecimal("200.00"),
                1L);
        assertThat(s.pmbRatio()).isEqualByComparingTo("0.3333");
    }

    @Test
    void computeSummary_perBeneficiaryRoundsHalfUpTo2dp() {
        // 100 / 3 = 33.3333... → 2dp HALF_UP = 33.33
        PmbSpendCalculator.Summary s = calculator.computeSummary(
                new BigDecimal("100.00"),
                BigDecimal.ZERO,
                3L);
        assertThat(s.pmbPaidPerBeneficiary()).isEqualByComparingTo("33.33");
    }
}

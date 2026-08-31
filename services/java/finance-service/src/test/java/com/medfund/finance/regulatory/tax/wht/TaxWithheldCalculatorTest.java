package com.medfund.finance.regulatory.tax.wht;

import com.medfund.shared.report.regulatory.RegulatoryReportGenerationException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaxWithheldCalculatorTest {

    private final TaxWithheldCalculator calc = new TaxWithheldCalculator();

    @Test
    void compute_fixtureMatchesGoldenValues() {
        TaxWithheldRawData raw = new TaxWithheldRawData(
                "Acme Insurance ZA (Pty) Ltd",
                "9012345678",
                new BigDecimal("4000000.00"), BigDecimal.ZERO,
                new BigDecimal("1200000.00"), BigDecimal.ZERO,
                new BigDecimal("500000.00"),  BigDecimal.ZERO,
                new BigDecimal("100000.00"),  BigDecimal.ZERO);
        TaxWithheldRates rates = new TaxWithheldRates(
                new BigDecimal("0.15"),
                new BigDecimal("0.15"),
                new BigDecimal("0.15"),
                new BigDecimal("0.15"));

        TaxWithheldCalculator.Computed c = calc.compute(raw, rates);

        assertThat(c.commissionWht()).isEqualByComparingTo("600000.00");
        assertThat(c.professionalFeesWht()).isEqualByComparingTo("180000.00");
        assertThat(c.dividendsWht()).isEqualByComparingTo("75000.00");
        assertThat(c.otherWht()).isEqualByComparingTo("15000.00");
        assertThat(c.totalPaymentsBase()).isEqualByComparingTo("5800000.00");
        assertThat(c.totalWithholdingPayable()).isEqualByComparingTo("870000.00");
    }

    @Test
    void compute_perLineOverride_wins_overTenantDefault_whenNonZero() {
        // Bespoke rate captured on the payment_run_item — treaty override or
        // manual correction. Overrides the tenant-default multiplication.
        TaxWithheldRawData raw = new TaxWithheldRawData(
                "A", "B",
                new BigDecimal("1000000.00"), new BigDecimal("250000.00"),  // 25% bespoke
                BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO);
        TaxWithheldRates rates = new TaxWithheldRates(
                new BigDecimal("0.15"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        TaxWithheldCalculator.Computed c = calc.compute(raw, rates);
        // Override wins → 250,000 (not 1M × 0.15 = 150,000).
        assertThat(c.commissionWht()).isEqualByComparingTo("250000.00");
    }

    @Test
    void compute_zeroRates_yieldsAllZeros() {
        TaxWithheldRawData raw = new TaxWithheldRawData(
                "A", "B",
                new BigDecimal("1000"), BigDecimal.ZERO,
                new BigDecimal("500"),  BigDecimal.ZERO,
                new BigDecimal("300"),  BigDecimal.ZERO,
                new BigDecimal("200"),  BigDecimal.ZERO);
        TaxWithheldCalculator.Computed c = calc.compute(raw, TaxWithheldRates.zero());

        assertThat(c.totalWithholdingPayable()).isEqualByComparingTo("0.00");
        assertThat(c.totalPaymentsBase()).isEqualByComparingTo("2000.00");
    }

    @Test
    void compute_nullRaw_rejects() {
        assertThatThrownBy(() -> calc.compute(null, TaxWithheldRates.zero()))
                .isInstanceOf(RegulatoryReportGenerationException.class);
    }

    @Test
    void compute_nullRates_rejects() {
        TaxWithheldRawData raw = new TaxWithheldRawData("A", "B",
                BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO);
        assertThatThrownBy(() -> calc.compute(raw, null))
                .isInstanceOf(RegulatoryReportGenerationException.class);
    }

    @Test
    void compute_roundsHalfUpTo2dp() {
        TaxWithheldRawData raw = new TaxWithheldRawData("A", "B",
                new BigDecimal("33.333"), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO);
        TaxWithheldRates rates = new TaxWithheldRates(
                new BigDecimal("0.15"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        // 33.333 * 0.15 = 4.99995 → HALF_UP 2dp = 5.00
        assertThat(calc.compute(raw, rates).commissionWht()).isEqualByComparingTo("5.00");
    }
}

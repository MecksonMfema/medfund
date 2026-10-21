package com.medfund.finance.regulatory.tax.vat;

import com.medfund.shared.report.regulatory.RegulatoryReportGenerationException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VatCalculatorTest {

    private final VatCalculator calc = new VatCalculator();

    @Test
    void compute_fixtureMatchesGoldenValues() {
        // ZA golden fixture: SARS standard 15 %, premiums exempt.
        VatRawData raw = new VatRawData(
                "Acme Insurance ZA (Pty) Ltd",
                "4900123456",
                new BigDecimal("100000000.00"),
                new BigDecimal("8000000.00"),
                new BigDecimal("4000000.00"),
                new BigDecimal("500000.00"),
                new BigDecimal("2000000.00"),
                new BigDecimal("1000000.00"),
                new BigDecimal("300000.00"));
        VatRates rates = new VatRates(
                new BigDecimal("0.00"),
                new BigDecimal("0.15"),
                new BigDecimal("0.15"),
                new BigDecimal("0.15"));

        VatCalculator.Computed c = calc.compute(raw, rates);

        // Output side
        assertThat(c.premiumBase()).isEqualByComparingTo("100000000.00");
        assertThat(c.premiumVat()).isEqualByComparingTo("0.00");
        assertThat(c.adminFeeVat()).isEqualByComparingTo("1200000.00");
        assertThat(c.commissionVat()).isEqualByComparingTo("600000.00");
        assertThat(c.otherOutputVat()).isEqualByComparingTo("75000.00");
        assertThat(c.outputStandardTotalBase()).isEqualByComparingTo("12500000.00");
        assertThat(c.outputStandardTotalVat()).isEqualByComparingTo("1875000.00");
        assertThat(c.outputZeroRatedTotalBase()).isEqualByComparingTo("100000000.00");

        // Input side
        assertThat(c.inputAdminExpensesVat()).isEqualByComparingTo("300000.00");
        assertThat(c.inputProfessionalFeesVat()).isEqualByComparingTo("150000.00");
        assertThat(c.inputOtherVat()).isEqualByComparingTo("45000.00");
        assertThat(c.inputTotalBase()).isEqualByComparingTo("3300000.00");
        assertThat(c.inputTotalVat()).isEqualByComparingTo("495000.00");

        // Summary
        assertThat(c.netVatPayable()).isEqualByComparingTo("1380000.00");
    }

    @Test
    void compute_zeroRates_yieldsAllZeros_forEveryVatAmount() {
        VatRawData raw = new VatRawData(
                "A", "B",
                new BigDecimal("1000"),
                new BigDecimal("500"),
                new BigDecimal("200"),
                new BigDecimal("100"),
                new BigDecimal("300"),
                new BigDecimal("200"),
                new BigDecimal("100"));
        VatCalculator.Computed c = calc.compute(raw, VatRates.zero());

        assertThat(c.outputStandardTotalVat()).isEqualByComparingTo("0.00");
        assertThat(c.inputTotalVat()).isEqualByComparingTo("0.00");
        assertThat(c.netVatPayable()).isEqualByComparingTo("0.00");
    }

    @Test
    void compute_inputHeavyPeriod_yieldsNegativeNetPayable() {
        // Common in a period with a big capex purchase and low sales.
        VatRawData raw = new VatRawData(
                "A", "B",
                BigDecimal.ZERO,
                new BigDecimal("100000"), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("1000000"), BigDecimal.ZERO, BigDecimal.ZERO);
        VatRates rates = new VatRates(
                BigDecimal.ZERO,
                new BigDecimal("0.15"),
                BigDecimal.ZERO,
                BigDecimal.ZERO);
        VatCalculator.Computed c = calc.compute(raw, rates);

        // output 15,000 − input 150,000 = -135,000 refundable
        assertThat(c.netVatPayable()).isEqualByComparingTo("-135000.00");
    }

    @Test
    void compute_nullRaw_rejectedWithRegulatoryException() {
        assertThatThrownBy(() -> calc.compute(null, VatRates.zero()))
                .isInstanceOf(RegulatoryReportGenerationException.class)
                .hasMessageContaining("raw data");
    }

    @Test
    void compute_nullRates_rejectedWithRegulatoryException() {
        VatRawData raw = new VatRawData("A", "B",
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        assertThatThrownBy(() -> calc.compute(raw, null))
                .isInstanceOf(RegulatoryReportGenerationException.class)
                .hasMessageContaining("rates");
    }

    @Test
    void compute_roundsHalfUpTo2dp() {
        // 33.333 * 0.15 = 4.99995 → round HALF_UP to 5.00
        VatRawData raw = new VatRawData("A", "B",
                BigDecimal.ZERO,
                new BigDecimal("33.333"),
                BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        VatRates rates = new VatRates(
                BigDecimal.ZERO,
                new BigDecimal("0.15"),
                BigDecimal.ZERO,
                BigDecimal.ZERO);
        VatCalculator.Computed c = calc.compute(raw, rates);
        assertThat(c.adminFeeVat()).isEqualByComparingTo("5.00");
    }
}

package com.medfund.finance.regulatory.tax.vat;

import com.medfund.shared.report.regulatory.RegulatoryReportGenerationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Derives output + input VAT amounts + net VAT payable for a
 * ({@code tenantId}, period) VAT return. The tenant's per-category
 * {@link VatRates} are resolved upstream by {@link VatRateReader}; this
 * class only multiplies bases by rates and sums them — no I/O, no
 * clock, deterministic per input.
 *
 * <p>Unlike IPEC / CMS / NAIC, VAT has no configurable statutory
 * parameters other than the rate itself; the rate table is authoritative
 * and admin-managed via the Phase 19 tenant_tax_config surface.
 */
@Slf4j
@Component
public class VatCalculator {

    /** Result of {@link #compute}: the flat set of numbers the shaper drops into cells. */
    public record Computed(
            BigDecimal premiumBase,
            BigDecimal premiumVat,
            BigDecimal adminFeeBase,
            BigDecimal adminFeeVat,
            BigDecimal commissionBase,
            BigDecimal commissionVat,
            BigDecimal otherOutputBase,
            BigDecimal otherOutputVat,
            BigDecimal outputStandardTotalBase,
            BigDecimal outputStandardTotalVat,
            BigDecimal outputZeroRatedTotalBase,
            BigDecimal inputAdminExpensesBase,
            BigDecimal inputAdminExpensesVat,
            BigDecimal inputProfessionalFeesBase,
            BigDecimal inputProfessionalFeesVat,
            BigDecimal inputOtherBase,
            BigDecimal inputOtherVat,
            BigDecimal inputTotalBase,
            BigDecimal inputTotalVat,
            BigDecimal netVatPayable) {}

    /**
     * Compute VAT amounts from raw bases + resolved rates. Both input
     * arguments must be non-null; the record constructors already default
     * missing values to {@link BigDecimal#ZERO}. Zero-rated categories
     * (premium under both ZIMRA + SARS) still surface a zero VAT amount
     * so the compliance-review chain sees the deliberate zero rather
     * than a missing row.
     *
     * <p>Amounts are rounded HALF_UP to 2 decimal places — matching the
     * expectation of every downstream regulator XLSX cell.
     */
    public Computed compute(VatRawData raw, VatRates rates) {
        if (raw == null) {
            throw new RegulatoryReportGenerationException("raw data required");
        }
        if (rates == null) {
            throw new RegulatoryReportGenerationException("rates required");
        }
        // Same divide-by-... rules that IPEC uses — assume rates on the input
        // side match those on the output side for the same category (a tenant
        // with different input rates would need a schema change).
        BigDecimal premiumVat        = round(raw.premiumBase().multiply(nvl(rates.premiumRate())));
        BigDecimal adminFeeVat       = round(raw.adminFeeBase().multiply(nvl(rates.adminFeeRate())));
        BigDecimal commissionVat     = round(raw.commissionBase().multiply(nvl(rates.commissionRate())));
        BigDecimal otherOutputVat    = round(raw.otherOutputBase().multiply(nvl(rates.otherRate())));
        BigDecimal inputAdminVat     = round(raw.adminExpensesBase().multiply(nvl(rates.adminFeeRate())));
        BigDecimal inputProfVat      = round(raw.professionalFeesBase().multiply(nvl(rates.otherRate())));
        BigDecimal inputOtherVat     = round(raw.otherInputBase().multiply(nvl(rates.otherRate())));

        BigDecimal outputStandardBase = round(raw.adminFeeBase().add(raw.commissionBase()).add(raw.otherOutputBase()));
        BigDecimal outputStandardVat  = round(adminFeeVat.add(commissionVat).add(otherOutputVat));
        BigDecimal outputZeroRatedBase = round(raw.premiumBase());
        BigDecimal inputTotalBase     = round(raw.adminExpensesBase().add(raw.professionalFeesBase()).add(raw.otherInputBase()));
        BigDecimal inputTotalVat      = round(inputAdminVat.add(inputProfVat).add(inputOtherVat));

        BigDecimal netPayable = round(outputStandardVat.subtract(inputTotalVat));

        return new Computed(
                round(raw.premiumBase()),        premiumVat,
                round(raw.adminFeeBase()),       adminFeeVat,
                round(raw.commissionBase()),     commissionVat,
                round(raw.otherOutputBase()),    otherOutputVat,
                outputStandardBase,              outputStandardVat,
                outputZeroRatedBase,
                round(raw.adminExpensesBase()),    inputAdminVat,
                round(raw.professionalFeesBase()), inputProfVat,
                round(raw.otherInputBase()),       inputOtherVat,
                inputTotalBase,                  inputTotalVat,
                netPayable);
    }

    private static BigDecimal nvl(BigDecimal r) {
        return r != null ? r : BigDecimal.ZERO;
    }

    private static BigDecimal round(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}

package com.medfund.finance.regulatory.tax.vat;

import java.math.BigDecimal;

/**
 * Raw VAT bases for a ({@code tenantId}, period) tuple — every monetary
 * value already converted to the tenant's country-native currency by
 * {@link VatRawDataProvider} implementations via
 * {@link com.medfund.finance.regulatory.service.RegulatoryFxPolicy}.
 *
 * <p>Phase 20 ships the {@link VatRawDataProvider} SPI with a stub
 * default implementation that returns zeroes and a WARN log; a later
 * sub-phase wires the concrete cross-service peer calls
 * (contributions-service for premium bases, finance-service internals
 * for admin fees + commission + expense purchases).
 */
public record VatRawData(
        String vendorName,
        String vatRegistrationNumber,
        BigDecimal premiumBase,
        BigDecimal adminFeeBase,
        BigDecimal commissionBase,
        BigDecimal otherOutputBase,
        BigDecimal adminExpensesBase,
        BigDecimal professionalFeesBase,
        BigDecimal otherInputBase) {

    public VatRawData {
        // Null → ZERO so downstream arithmetic is safe on partial peer failures.
        premiumBase = orZero(premiumBase);
        adminFeeBase = orZero(adminFeeBase);
        commissionBase = orZero(commissionBase);
        otherOutputBase = orZero(otherOutputBase);
        adminExpensesBase = orZero(adminExpensesBase);
        professionalFeesBase = orZero(professionalFeesBase);
        otherInputBase = orZero(otherInputBase);
    }

    private static BigDecimal orZero(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}

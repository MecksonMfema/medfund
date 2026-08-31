package com.medfund.finance.regulatory.tax.vat;

import java.math.BigDecimal;

/**
 * The per-category VAT rates the calculator applies to the raw bases.
 * Resolved by {@link VatRateReader} from {@code public.tenant_tax_config}
 * (Phase 19) — one row per {@code (tenant_id, tax_type='VAT',
 * transaction_category, currency)} tuple, effective-dated so a mid-year
 * ZIMRA/SARS rate change is honoured cleanly.
 *
 * <p>All rates are decimals (0.15 = 15 %); insurance premiums are
 * zero-rated in both ZW and ZA today (the V174 seed reflects this).
 */
public record VatRates(
        BigDecimal premiumRate,
        BigDecimal adminFeeRate,
        BigDecimal commissionRate,
        BigDecimal otherRate) {

    /**
     * All-zero rates — used when no tenant_tax_config rows exist for the
     * period (a fresh tenant that hasn't yet been seeded). The report
     * still renders, with obviously-zero output VAT that a compliance
     * reviewer catches immediately rather than a plausibly-populated
     * but wrong return.
     */
    public static VatRates zero() {
        return new VatRates(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }
}

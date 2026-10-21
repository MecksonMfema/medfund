package com.medfund.finance.regulatory.tax.wht;

import java.math.BigDecimal;

/**
 * Per-category withholding-tax rates the calculator applies to the raw
 * payment bases. Resolved by {@link TaxWithheldRateReader} from
 * {@code public.tenant_tax_config} (Phase 19) — one row per
 * {@code (tenant_id, tax_type='WITHHOLDING', transaction_category,
 * currency)} tuple, effective-dated so a mid-year ZIMRA/SARS rate
 * change is honoured cleanly.
 *
 * <p>All rates are decimals (0.15 = 15 %); ZIMRA WHT on commission is
 * 10 %, SARS is 15 %; the V174 seed reflects those defaults.
 */
public record TaxWithheldRates(
        BigDecimal commissionRate,
        BigDecimal professionalFeesRate,
        BigDecimal dividendsRate,
        BigDecimal otherRate) {

    /** All-zero rates — used when no tenant_tax_config rows match the period. */
    public static TaxWithheldRates zero() {
        return new TaxWithheldRates(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }
}

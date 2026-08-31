package com.medfund.finance.regulatory.tax.wht;

import java.math.BigDecimal;

/**
 * Raw withholding-tax bases for a ({@code tenantId}, period) tuple —
 * every monetary value already converted to the tenant's country-native
 * currency by {@link TaxWithheldRawDataProvider} implementations via
 * {@link com.medfund.finance.regulatory.service.RegulatoryFxPolicy}.
 *
 * <p>Bases are the {@code SUM(amount)} of {@code payment_run_items}
 * grouped by category over the reporting period; the per-item
 * {@code withholding_tax_pct} column may override the tenant default
 * (a bespoke rate for a single vendor) — the aggregator materialises
 * both the base and any pre-computed WHT the payment run captured, and
 * the calculator honours the pre-computed WHT when present or falls
 * back to base × tenant-default-rate otherwise. Phase 21 ships the SPI
 * with a stub that returns zeroes.
 */
public record TaxWithheldRawData(
        String agentName,
        String taxIdentificationNumber,
        BigDecimal commissionBase,
        BigDecimal commissionOverrideWht,
        BigDecimal professionalFeesBase,
        BigDecimal professionalFeesOverrideWht,
        BigDecimal dividendsBase,
        BigDecimal dividendsOverrideWht,
        BigDecimal otherBase,
        BigDecimal otherOverrideWht) {

    public TaxWithheldRawData {
        commissionBase = orZero(commissionBase);
        commissionOverrideWht = orZero(commissionOverrideWht);
        professionalFeesBase = orZero(professionalFeesBase);
        professionalFeesOverrideWht = orZero(professionalFeesOverrideWht);
        dividendsBase = orZero(dividendsBase);
        dividendsOverrideWht = orZero(dividendsOverrideWht);
        otherBase = orZero(otherBase);
        otherOverrideWht = orZero(otherOverrideWht);
    }

    private static BigDecimal orZero(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}

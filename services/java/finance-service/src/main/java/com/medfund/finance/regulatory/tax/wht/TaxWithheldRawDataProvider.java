package com.medfund.finance.regulatory.tax.wht;

import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.UUID;

/**
 * SPI for pulling raw Withholding-Tax return bases. Phase 21 ships the
 * SPI + a {@link StubTaxWithheldRawDataProvider} default that returns
 * zeroes so the end-to-end submit → XLSX pipeline is exercisable
 * without wiring the concrete finance-service peer call.
 *
 * <p>A later sub-phase (or the first real ZW/ZA tenant onboarding)
 * swaps in a concrete Spring {@code @Component} that aggregates
 * {@code payment_run_items} rows over the reporting period:
 * {@code SUM(amount)} per (category, currency) plus the pre-computed
 * {@code SUM(amount * withholding_tax_pct / 100)} where the per-item
 * pct is set (else 0 — the calculator applies the tenant default).
 */
public interface TaxWithheldRawDataProvider {

    Mono<TaxWithheldRawData> load(UUID tenantId, LocalDate periodStart, LocalDate periodEnd);
}

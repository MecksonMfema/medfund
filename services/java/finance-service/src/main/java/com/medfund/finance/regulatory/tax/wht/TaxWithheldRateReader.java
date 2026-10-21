package com.medfund.finance.regulatory.tax.wht;

import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.UUID;

/**
 * SPI for pulling the effective WHT rate set for a ({@code tenantId},
 * country, currency, effectiveDate) tuple from
 * {@code public.tenant_tax_config} (Phase 19) — filtered by
 * {@code tax_type='WITHHOLDING'}. Returns {@link TaxWithheldRates#zero()}
 * when no rows match (fresh tenant that hasn't yet been seeded).
 *
 * <p>Phase 21 ships the SPI + a {@link StubTaxWithheldRateReader}; a
 * later sub-phase provides an R2DBC implementation that queries the
 * seeded rows via {@code DatabaseClient} — same pattern as
 * {@link com.medfund.finance.regulatory.tax.vat.VatRateReader}.
 */
public interface TaxWithheldRateReader {

    Mono<TaxWithheldRates> resolve(UUID tenantId, String countryCode, String currency, LocalDate effectiveDate);
}

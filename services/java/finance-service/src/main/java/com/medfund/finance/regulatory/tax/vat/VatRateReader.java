package com.medfund.finance.regulatory.tax.vat;

import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.UUID;

/**
 * SPI for pulling the effective VAT rate set for a ({@code tenantId},
 * country, currency, effectiveDate) tuple from
 * {@code public.tenant_tax_config} (Phase 19). Returns
 * {@link VatRates#zero()} when no rows match — a fresh tenant that
 * hasn't yet been seeded still renders a valid (empty) return.
 *
 * <p>Phase 20 ships the SPI + a {@link StubVatRateReader} that always
 * returns zero rates; a later sub-phase provides a
 * {@code R2dbcTenantVatRateReader} that queries the seeded rows via
 * {@code DatabaseClient} — same pattern as
 * {@link com.medfund.finance.regulatory.naic.R2dbcUsTenantNaicConfigReader}
 * (Phase 14 REG16).
 */
public interface VatRateReader {

    Mono<VatRates> resolve(UUID tenantId, String countryCode, String currency, LocalDate effectiveDate);
}

package com.medfund.finance.regulatory.tax.vat;

import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.UUID;

/**
 * SPI for pulling raw VAT Return bases. Phase 20 ships the SPI + a
 * {@link StubVatRawDataProvider} default that returns zeroes so the
 * end-to-end submit → XLSX pipeline is exercisable without wiring every
 * contributions-service / finance-service peer call.
 *
 * <p>A later sub-phase (or the first real ZW/ZA tenant onboarding)
 * swaps in a concrete Spring {@code @Component} that:
 * <ul>
 *   <li>Aggregates {@code SUM(premium_amount)} for the period via
 *       {@code CrossServiceCallHelper.guarded(...)} to contributions-service.</li>
 *   <li>Reads admin-fee, commission and expense buckets from
 *       finance-service internals (payment_run + commission tables).</li>
 *   <li>Converts every non-native amount via
 *       {@link com.medfund.finance.regulatory.service.RegulatoryFxPolicy}.</li>
 * </ul>
 *
 * <p>Only one bean should be registered — Spring's
 * {@link org.springframework.context.annotation.Primary} on the concrete
 * bean marks it authoritative and demotes the stub.
 */
public interface VatRawDataProvider {

    Mono<VatRawData> load(UUID tenantId, LocalDate periodStart, LocalDate periodEnd);
}

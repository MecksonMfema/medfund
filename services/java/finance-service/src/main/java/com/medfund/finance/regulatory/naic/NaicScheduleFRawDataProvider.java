package com.medfund.finance.regulatory.naic;

import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.UUID;

/**
 * SPI for pulling raw NAIC Schedule F inputs. Phase 13 ships the SPI + a
 * {@link StubNaicScheduleFRawDataProvider} default that returns zeroes so
 * the end-to-end submit → XLSX pipeline is exercisable without wiring
 * every finance-service reinsurance / claims-service peer call.
 *
 * <p>Phase 13b (or the first real US tenant onboarding) swaps in a
 * concrete Spring {@code @Component} that:
 * <ul>
 *   <li>Reads company identity ({@code companyName} / {@code naicCode} /
 *       {@code groupCode} / {@code fein} / {@code stateOfDomicile}) from
 *       {@code public.us_tenant_naic_config} (delivered by Phase 14).</li>
 *   <li>Calls finance-service reinsurance internals for ceded premiums +
 *       losses paid + losses unpaid per reinsurer stratum
 *       (affiliated / authorized / unauthorized / certified) via
 *       {@code CrossServiceCallHelper.guarded(...)}.</li>
 *   <li>Calls claims-service for outstanding case + IBNR reserves ceded
 *       per reinsurer stratum.</li>
 *   <li>Converts every non-USD amount via
 *       {@link com.medfund.finance.regulatory.service.RegulatoryFxPolicy}.</li>
 * </ul>
 *
 * <p>Only one bean should be registered — Spring's
 * {@link org.springframework.context.annotation.Primary} on the concrete
 * bean marks it authoritative and demotes the stub.
 */
public interface NaicScheduleFRawDataProvider {

    Mono<NaicScheduleFRawData> load(UUID tenantId, LocalDate periodStart, LocalDate periodEnd);
}

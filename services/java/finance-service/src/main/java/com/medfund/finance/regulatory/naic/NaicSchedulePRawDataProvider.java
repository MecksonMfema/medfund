package com.medfund.finance.regulatory.naic;

import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.UUID;

/**
 * SPI for pulling raw NAIC Schedule P inputs. Phase 12 ships the SPI + a
 * {@link StubNaicSchedulePRawDataProvider} default that returns zeroes so
 * the end-to-end submit → XLSX pipeline is exercisable without wiring
 * every claims-service / contributions-service peer call.
 *
 * <p>Phase 12b (or the first real US tenant onboarding) swaps in a
 * concrete Spring {@code @Component} that:
 * <ul>
 *   <li>Reads company identity ({@code companyName} / {@code naicCode} /
 *       {@code groupCode} / {@code fein} / {@code stateOfDomicile}) from
 *       {@code public.us_tenant_naic_config} (delivered by Phase 14).</li>
 *   <li>Calls claims-service via {@code CrossServiceCallHelper.guarded(...)}
 *       for per-accident-year paid + case reserves + IBNR.</li>
 *   <li>Calls contributions-service for per-accident-year earned premium.</li>
 *   <li>Converts every non-USD amount via
 *       {@link com.medfund.finance.regulatory.service.RegulatoryFxPolicy}.</li>
 * </ul>
 *
 * <p>Only one bean should be registered — Spring's
 * {@link org.springframework.context.annotation.Primary} on the concrete
 * bean marks it authoritative and demotes the stub.
 */
public interface NaicSchedulePRawDataProvider {

    Mono<NaicSchedulePRawData> load(UUID tenantId, LocalDate periodStart, LocalDate periodEnd);
}

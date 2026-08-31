package com.medfund.finance.regulatory.ipec;

import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.UUID;

/**
 * SPI for pulling raw IPEC quarterly return inputs. Phase 10 ships the
 * SPI + a {@link StubIpecRawDataProvider} default that returns zeroes so
 * the end-to-end submit→XLSX pipeline is exercisable without wiring
 * every contributions-service / claims-service peer call.
 *
 * <p>Phase 10b (or the first real ZW tenant onboarding) swaps in a
 * concrete Spring {@code @Component} that:
 * <ul>
 *   <li>Reads the tenant's balance-sheet snapshot from finance-service internals.</li>
 *   <li>Calls contributions-service via {@code CrossServiceCallHelper.guarded(...)}
 *       for per-line GWP + ceded reinsurance.</li>
 *   <li>Calls claims-service for OSC + IBNR reserves.</li>
 *   <li>Converts every non-ZWL amount via
 *       {@link com.medfund.finance.regulatory.service.RegulatoryFxPolicy}.</li>
 * </ul>
 *
 * <p>Only one bean should be registered — Spring's
 * {@link org.springframework.context.annotation.Primary} on the concrete
 * bean marks it authoritative and demotes the stub.
 */
public interface IpecRawDataProvider {

    Mono<IpecRawData> load(UUID tenantId, LocalDate periodStart, LocalDate periodEnd);
}

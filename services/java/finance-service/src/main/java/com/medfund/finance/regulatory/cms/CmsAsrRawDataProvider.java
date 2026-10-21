package com.medfund.finance.regulatory.cms;

import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.UUID;

/**
 * SPI for pulling raw CMS Annual Statutory Return inputs. Phase 11 ships
 * the SPI + a {@link StubCmsAsrRawDataProvider} default that returns
 * zeroes so the end-to-end submit → XLSX pipeline is exercisable without
 * wiring every contributions-service / claims-service peer call.
 *
 * <p>Phase 11b (or the first real ZA medical-scheme tenant onboarding)
 * swaps in a concrete Spring {@code @Component} that:
 * <ul>
 *   <li>Reads the tenant's balance-sheet snapshot from finance-service internals.</li>
 *   <li>Calls contributions-service via {@code CrossServiceCallHelper.guarded(...)}
 *       for gross + net contributions.</li>
 *   <li>Calls claims-service for risk claims incurred over the reporting period.</li>
 *   <li>Reads admin + broker + managed-care spend from finance-service internals
 *       (payment_run + commission tables).</li>
 *   <li>Reads membership counts from user-service (principal + dependant totals)
 *       + pensioner ratio (age &gt;= 65).</li>
 *   <li>Converts every non-ZAR amount via
 *       {@link com.medfund.finance.regulatory.service.RegulatoryFxPolicy}.</li>
 * </ul>
 *
 * <p>Only one bean should be registered — Spring's
 * {@link org.springframework.context.annotation.Primary} on the concrete
 * bean marks it authoritative and demotes the stub.
 */
public interface CmsAsrRawDataProvider {

    Mono<CmsAsrRawData> load(UUID tenantId, LocalDate periodStart, LocalDate periodEnd);
}

package com.medfund.finance.regulatory.pmb;

import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.UUID;

/**
 * SPI for pulling raw PMB Spend report inputs. Phase 18 ships the SPI + a
 * {@link StubPmbSpendRawDataProvider} default that returns zeroes so the
 * end-to-end submit → XLSX pipeline is exercisable without wiring the
 * claims-service peer call.
 *
 * <p>A downstream sub-phase (or the first real ZA medical-scheme tenant
 * onboarding) swaps in a concrete Spring {@code @Component} that:
 * <ul>
 *   <li>Aggregates {@code SUM(paid_amount) GROUP BY is_pmb, pmb_condition_code,
 *       currency} on the tenant's {@code claims} table via
 *       {@code CrossServiceCallHelper.guarded(...)} to claims-service.</li>
 *   <li>Rolls each condition code up into its {@link PmbCategory} via
 *       {@link PmbCategory#forCode(String)}.</li>
 *   <li>Converts every non-ZAR bucket via
 *       {@link com.medfund.finance.regulatory.service.RegulatoryFxPolicy}.</li>
 *   <li>Reads scheme identity + beneficiary counts from user-service
 *       (principal + dependant totals).</li>
 * </ul>
 *
 * <p>Only one bean should be registered — Spring's
 * {@link org.springframework.context.annotation.Primary} on the concrete
 * bean marks it authoritative and demotes the stub.
 */
public interface PmbSpendRawDataProvider {

    Mono<PmbSpendRawData> load(UUID tenantId, LocalDate periodStart, LocalDate periodEnd);
}

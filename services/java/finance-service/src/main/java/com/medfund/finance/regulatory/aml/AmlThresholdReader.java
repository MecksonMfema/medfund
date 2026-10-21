package com.medfund.finance.regulatory.aml;

import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.UUID;

/**
 * SPI resolving the effective AML thresholds for a tenant as of a given
 * date. Consumed by {@link AmlSummaryReportShaper}. The concrete
 * R2DBC-backed implementation ({@code R2dbcAmlThresholdReader}) queries
 * {@code public.tenant_aml_threshold_config} for the row whose
 * {@code effective_from} ≤ asOf and (nullable) {@code effective_to} > asOf,
 * per category × currency.
 *
 * <p>Falls back to {@link AmlThresholds#empty()} when nothing is configured —
 * downstream the calculator treats zero-threshold as "flag everything",
 * so the operator sees an obvious "populate your thresholds" signal
 * rather than a plausibly-empty return.
 */
public interface AmlThresholdReader {

    Mono<AmlThresholds> resolve(UUID tenantId, String countryCode, String currency, LocalDate asOf);
}

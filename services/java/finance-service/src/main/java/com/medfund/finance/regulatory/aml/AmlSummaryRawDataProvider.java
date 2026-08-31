package com.medfund.finance.regulatory.aml;

import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.UUID;

/**
 * SPI aggregating {@link AmlSummaryRawData} for a (tenant, period). The
 * concrete implementation is deferred to Phase 25b — it needs cross-service
 * queries (contributions-service for premium counts, finance-service for
 * payments, and a self-query on {@code suspicious_transaction_alert} for
 * the STR side). {@link StubAmlSummaryRawDataProvider} keeps the wiring
 * live in the meantime.
 */
public interface AmlSummaryRawDataProvider {

    Mono<AmlSummaryRawData> load(UUID tenantId, LocalDate periodStart, LocalDate periodEnd);
}

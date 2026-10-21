package com.medfund.finance.regulatory.tax.wht;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Default {@link TaxWithheldRawDataProvider} placeholder — returns
 * zeroes and emits a WARN log so a live submission against an
 * un-integrated tenant yields an obviously-empty return.
 */
@Slf4j
@Component
public class StubTaxWithheldRawDataProvider implements TaxWithheldRawDataProvider {

    @Override
    public Mono<TaxWithheldRawData> load(UUID tenantId, LocalDate periodStart, LocalDate periodEnd) {
        log.warn("[wht-shape] StubTaxWithheldRawDataProvider active — tenant={} period={}..{}"
                        + ". Real cross-service wiring is deferred.",
                tenantId, periodStart, periodEnd);
        return Mono.just(new TaxWithheldRawData(
                null, null,
                BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO));
    }
}

package com.medfund.finance.regulatory.aml;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Fallback {@link AmlThresholdReader} that returns {@link AmlThresholds#empty()}
 * with a WARN log. Kicks in only when no concrete reader bean is on the
 * classpath — the R2DBC implementation
 * ({@code R2dbcAmlThresholdReader}) is deferred until the AML admin CRUD
 * surface has real seed data. Same pattern as {@code StubVatRateReader}
 * (Phase 20) + {@code StubTaxWithheldRateReader} (Phase 21).
 */
@Slf4j
@Component
@ConditionalOnMissingBean(AmlThresholdReader.class)
public class StubAmlThresholdReader implements AmlThresholdReader {

    @Override
    public Mono<AmlThresholds> resolve(UUID tenantId, String countryCode, String currency, LocalDate asOf) {
        log.warn("[aml-threshold-reader:stub] no concrete reader wired — returning empty thresholds "
                + "for tenant={} country={} currency={} asOf={}. Zero-thresholds cause the calculator "
                + "to flag every transaction as above-threshold; configure per-category rows via the "
                + "admin CRUD or wire R2dbcAmlThresholdReader to get real data.",
                tenantId, countryCode, currency, asOf);
        return Mono.just(AmlThresholds.empty());
    }
}

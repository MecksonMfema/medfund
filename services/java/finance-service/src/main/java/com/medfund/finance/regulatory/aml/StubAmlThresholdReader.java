package com.medfund.finance.regulatory.aml;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Fallback {@link AmlThresholdReader} that returns {@link AmlThresholds#empty()}
 * with a WARN log. Registered unconditionally today because the R2DBC
 * implementation ({@code R2dbcAmlThresholdReader}) is deferred until the AML
 * admin CRUD surface has real seed data; when it lands, mark it {@code @Primary}
 * to take precedence.
 * (@ConditionalOnMissingBean on a @Component self-excludes at scan time —
 *  the guard has to live on a @Bean method in a @Configuration class.)
 */
@Slf4j
@Component
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

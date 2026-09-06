package com.medfund.finance.regulatory.aml;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.UUID;

/**
 * Fallback {@link AmlSummaryRawDataProvider} — returns an all-zero raw
 * data set + WARN log. Same "obvious-empty over plausible-wrong" contract
 * as the other Phase-16 stub providers. Registered unconditionally today
 * because the concrete provider is deferred to Phase 25b; when that lands,
 * mark the real impl {@code @Primary} to take precedence.
 * (@ConditionalOnMissingBean on a @Component self-excludes at scan time —
 *  the guard has to live on a @Bean method in a @Configuration class.)
 */
@Slf4j
@Component
public class StubAmlSummaryRawDataProvider implements AmlSummaryRawDataProvider {

    @Override
    public Mono<AmlSummaryRawData> load(UUID tenantId, LocalDate periodStart, LocalDate periodEnd) {
        log.warn("[aml-raw-data:stub] no concrete provider wired — returning zero counts + amounts "
                + "for tenant={} period={} to {}. The XLSX will render but the numbers are placeholders.",
                tenantId, periodStart, periodEnd);
        return Mono.just(new AmlSummaryRawData(
                "(reporting entity name not configured)",
                "(regulator reference not configured)",
                new EnumMap<>(AmlSummaryRawData.ActivityCategory.class),
                new EnumMap<>(AmlSummaryRawData.ActivityCategory.class),
                new EnumMap<>(AmlSummaryRawData.StrStatus.class),
                BigDecimal.ZERO));
    }
}

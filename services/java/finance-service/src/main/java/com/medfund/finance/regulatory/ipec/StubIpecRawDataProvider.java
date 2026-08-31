package com.medfund.finance.regulatory.ipec;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Default {@link IpecRawDataProvider} placeholder — returns zeroes and
 * emits a WARN log so a live submission against an un-integrated tenant
 * yields an obviously-empty return that fails compliance review rather
 * than a plausibly-populated but wrong return.
 *
 * <p>Phase 10b replaces this with a concrete cross-service peer-call
 * provider (mark the replacement {@code @Primary} to demote this stub).
 */
@Slf4j
@Component
public class StubIpecRawDataProvider implements IpecRawDataProvider {

    @Override
    public Mono<IpecRawData> load(UUID tenantId, LocalDate periodStart, LocalDate periodEnd) {
        log.warn("[ipec-shape] StubIpecRawDataProvider active — tenant={} period={}..{}"
                        + ". Real cross-service wiring is deferred to Phase 10b.",
                tenantId, periodStart, periodEnd);
        return Mono.just(new IpecRawData(
                null, null,
                BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO));
    }
}

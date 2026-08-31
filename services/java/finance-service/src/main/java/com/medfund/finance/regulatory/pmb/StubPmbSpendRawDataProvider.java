package com.medfund.finance.regulatory.pmb;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/**
 * Default {@link PmbSpendRawDataProvider} placeholder — returns zeroes and
 * emits a WARN log so a live submission against an un-integrated tenant
 * yields an obviously-empty return that fails compliance review rather
 * than a plausibly-populated but wrong return.
 *
 * <p>A later sub-phase replaces this with a concrete cross-service
 * peer-call provider (mark the replacement {@code @Primary} to demote
 * this stub).
 */
@Slf4j
@Component
public class StubPmbSpendRawDataProvider implements PmbSpendRawDataProvider {

    @Override
    public Mono<PmbSpendRawData> load(UUID tenantId, LocalDate periodStart, LocalDate periodEnd) {
        log.warn("[pmb-shape] StubPmbSpendRawDataProvider active — tenant={} period={}..{}"
                        + ". Real claims-service wiring is deferred.",
                tenantId, periodStart, periodEnd);
        Map<PmbCategory, BigDecimal> zeroPaid = new EnumMap<>(PmbCategory.class);
        Map<PmbCategory, Long> zeroCount = new EnumMap<>(PmbCategory.class);
        for (PmbCategory c : PmbCategory.values()) {
            zeroPaid.put(c, BigDecimal.ZERO);
            zeroCount.put(c, 0L);
        }
        return Mono.just(new PmbSpendRawData(
                null, null,
                0L,
                zeroPaid,
                zeroCount,
                BigDecimal.ZERO));
    }
}

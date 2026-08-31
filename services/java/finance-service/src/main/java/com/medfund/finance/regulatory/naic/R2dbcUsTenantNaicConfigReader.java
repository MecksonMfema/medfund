package com.medfund.finance.regulatory.naic;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Concrete {@link UsTenantNaicConfigReader} backed by a direct R2DBC query
 * against {@code public.us_tenant_naic_config} (Phase 14 REG16). Phase 12 /
 * 13's {@code NaicSchedule*JobService} Optional-inject this reader; when
 * present (this bean), submit consults it per request and 422s the caller
 * when no effective row exists.
 *
 * <p>An "effective" row is one whose {@code effective_from ≤ today} and
 * {@code effective_to} is either null or {@code ≥ today}. The reader only
 * probes for existence — the shaper's raw-data provider (Phase 12b / 13b)
 * re-reads once for the identity fields it needs.
 *
 * <p>Uses the {@code public.} prefix per the {@code bug_public_prefix_silent_rollback}
 * memory — {@code us_tenant_naic_config} is a platform-wide table (V171),
 * not a tenant-schema table.
 *
 * <p>Errors resolve to {@code Mono.just(false)} per the SPI contract — a
 * database blip fails closed (over-reject) rather than over-permitting a
 * regulator submission.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class R2dbcUsTenantNaicConfigReader implements UsTenantNaicConfigReader {

    private final DatabaseClient databaseClient;

    @Override
    public Mono<Boolean> hasEffectiveConfig(UUID tenantId) {
        if (tenantId == null) return Mono.just(false);
        LocalDate today = LocalDate.now();
        return databaseClient.sql("""
                SELECT 1
                  FROM public.us_tenant_naic_config
                 WHERE tenant_id = :tenantId
                   AND effective_from <= :today
                   AND (effective_to IS NULL OR effective_to >= :today)
                 LIMIT 1
                """)
                .bind("tenantId", tenantId)
                .bind("today", today)
                .fetch()
                .first()
                .map(row -> Boolean.TRUE)
                .defaultIfEmpty(Boolean.FALSE)
                .onErrorResume(err -> {
                    log.warn("[us-naic-config] hasEffectiveConfig lookup failed for tenant={}: {} — failing closed",
                            tenantId, err.getMessage());
                    return Mono.just(Boolean.FALSE);
                });
    }
}

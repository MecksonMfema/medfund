package com.medfund.finance.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Reader for public-schema tenant configuration owned by tenancy-service.
 *
 * <p>Uses the {@code public.} prefix as required for cross-tenant / V105+
 * platform-wide tables (see auto-memory {@code bug_public_prefix_silent_rollback}
 * for why tenant tables must stay unqualified).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TenantConfigClient {

    /** Platform-default threshold when no tenant-config row exists. Matches V128 column defaults. */
    private static final BigDecimal DEFAULT_THRESHOLD_AMOUNT = new BigDecimal("500");
    private static final String DEFAULT_THRESHOLD_CURRENCY = "USD";

    private final DatabaseClient databaseClient;

    public Mono<AdvancePaymentThreshold> getAdvancePaymentThreshold(UUID tenantId) {
        if (tenantId == null) {
            return Mono.just(defaultThreshold());
        }
        return databaseClient.sql("""
                SELECT approval_threshold_amount, approval_threshold_currency
                  FROM public.tenant_advance_payment_config
                 WHERE tenant_id = :tid
                """)
                .bind("tid", tenantId)
                .map((row, meta) -> new AdvancePaymentThreshold(
                        row.get("approval_threshold_amount", BigDecimal.class),
                        row.get("approval_threshold_currency", String.class)))
                .one()
                .defaultIfEmpty(defaultThreshold())
                .onErrorResume(err -> {
                    log.warn("[advance-threshold] lookup failed for tenant {}: {} — falling back to platform default",
                            tenantId, err.getMessage());
                    return Mono.just(defaultThreshold());
                });
    }

    private AdvancePaymentThreshold defaultThreshold() {
        return new AdvancePaymentThreshold(DEFAULT_THRESHOLD_AMOUNT, DEFAULT_THRESHOLD_CURRENCY);
    }

    public record AdvancePaymentThreshold(BigDecimal amount, String currencyCode) {}
}

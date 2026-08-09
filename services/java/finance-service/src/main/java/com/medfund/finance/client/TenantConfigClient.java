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

    /** Platform-default auto-CTC config when no tenant-config row exists. Matches V129 column defaults. */
    private static final CtcAutoConfig DEFAULT_CTC_AUTO_CONFIG =
            new CtcAutoConfig(false, BigDecimal.ZERO, null, "USD");

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

    /**
     * Reads per-tenant auto-CTC configuration from {@code public.tenant_ctc_auto_config}
     * (V129). Returns {@link #DEFAULT_CTC_AUTO_CONFIG} (enabled=false) when the tenant
     * has no row — auto-drafting stays off until the tenant admin opts in.
     */
    public Mono<CtcAutoConfig> getCtcAutoConfig(UUID tenantId) {
        if (tenantId == null) {
            return Mono.just(DEFAULT_CTC_AUTO_CONFIG);
        }
        return databaseClient.sql("""
                SELECT enabled, min_member_balance_threshold, max_per_ctc_amount, threshold_currency
                  FROM public.tenant_ctc_auto_config
                 WHERE tenant_id = :tid
                """)
                .bind("tid", tenantId)
                .map((row, meta) -> new CtcAutoConfig(
                        Boolean.TRUE.equals(row.get("enabled", Boolean.class)),
                        row.get("min_member_balance_threshold", BigDecimal.class),
                        row.get("max_per_ctc_amount", BigDecimal.class),
                        row.get("threshold_currency", String.class)))
                .one()
                .defaultIfEmpty(DEFAULT_CTC_AUTO_CONFIG)
                .onErrorResume(err -> {
                    log.warn("[ctc-auto-config] lookup failed for tenant {}: {} — falling back to disabled default",
                            tenantId, err.getMessage());
                    return Mono.just(DEFAULT_CTC_AUTO_CONFIG);
                });
    }

    private AdvancePaymentThreshold defaultThreshold() {
        return new AdvancePaymentThreshold(DEFAULT_THRESHOLD_AMOUNT, DEFAULT_THRESHOLD_CURRENCY);
    }

    public record AdvancePaymentThreshold(BigDecimal amount, String currencyCode) {}

    /**
     * @param enabled                     master toggle — false = auto-drafting off
     * @param minMemberBalanceThreshold   threshold value in {@code thresholdCurrency}
     * @param maxPerCtcAmount             optional per-CTC cap (in {@code thresholdCurrency}), null = no cap
     * @param thresholdCurrency           ISO-4217 code for both threshold + cap
     */
    public record CtcAutoConfig(
            boolean enabled,
            BigDecimal minMemberBalanceThreshold,
            BigDecimal maxPerCtcAmount,
            String thresholdCurrency
    ) {}
}

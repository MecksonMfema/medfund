package com.medfund.shared.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Shared reader for public-schema tenant configuration owned by
 * tenancy-service, consumed by report generation in the line services.
 *
 * <p>Uses the {@code public.} prefix as required for cross-tenant / V105+
 * platform-wide tables (see {@code bug_public_prefix_silent_rollback} for
 * why tenant-schema tables must stay unqualified).
 *
 * <p>Distinct bean name {@code highCostClaimantConfigClient}: finance-service
 * ships its own {@code TenantConfigClient} (advance-payment / auto-CTC config)
 * whose default bean name is {@code tenantConfigClient} — both classes sit on
 * the classpath of every line service, and two same-named {@code @Component}s
 * would trip a ConflictingBeanDefinitionException. Bean names are irrelevant to
 * the constructor/type injection both services use.
 */
@Slf4j
@Component("highCostClaimantConfigClient")
@RequiredArgsConstructor
public class TenantConfigClient {

    private final DatabaseClient databaseClient;

    /**
     * Reads the per-tenant high-cost claimant threshold from
     * {@code public.tenant_high_cost_claimant_config} (V132). Returns an
     * EMPTY Mono when the tenant has no row — the HIGH_COST_CLAIMANT report
     * treats that as a config gap and renders empty with a warning (G46),
     * distinct from a fabricated platform default.
     */
    public Mono<HighCostClaimantConfig> getHighCostClaimantConfig(UUID tenantId) {
        if (tenantId == null) {
            return Mono.empty();
        }
        return databaseClient.sql("""
                SELECT threshold_amount, currency_code
                  FROM public.tenant_high_cost_claimant_config
                 WHERE tenant_id = :tid
                """)
                .bind("tid", tenantId)
                .map((row, meta) -> new HighCostClaimantConfig(
                        row.get("threshold_amount", BigDecimal.class),
                        row.get("currency_code", String.class)))
                .one()
                .onErrorResume(err -> {
                    log.warn("[high-cost-config] lookup failed for tenant {}: {} — treating as unconfigured",
                            tenantId, err.getMessage());
                    return Mono.empty();
                });
    }

    /**
     * @param thresholdAmount cumulative paid claims (native to
     *                         {@code currencyCode}) that flag a member as high-cost
     * @param currencyCode    ISO-4217 code the threshold is denominated in
     */
    public record HighCostClaimantConfig(BigDecimal thresholdAmount, String currencyCode) {}
}

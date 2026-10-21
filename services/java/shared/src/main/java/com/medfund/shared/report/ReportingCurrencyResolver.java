package com.medfund.shared.report;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Locale;
import java.util.UUID;

/**
 * Resolves the ISO-4217 currency to render a report in. Precedence:
 *
 * <ol>
 *   <li>Explicit {@code ?reportingCurrency=} query param (upper-cased).</li>
 *   <li>Tenant default from {@code public.tenant_currency_config.is_default}
 *       (V104 + V113).</li>
 *   <li>Hard fallback to {@code "USD"} — only reached when a tenant has no
 *       configured default at all, which the tenancy service provisions on
 *       tenant creation, so real tenants never see it.</li>
 * </ol>
 *
 * <p>Reads {@code public.tenant_currency_config} directly rather than
 * hopping through the tenancy-service HTTP API — same rationale as
 * {@code FxConverter} for {@code public.exchange_rates}. Report generation
 * has to make the FX lookup anyway; the extra hop for a currency code
 * would double the round-trip cost.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReportingCurrencyResolver {

    /** Platform fallback when no tenant currency config exists. */
    static final String PLATFORM_DEFAULT = "USD";

    private final DatabaseClient databaseClient;

    /**
     * @param tenantId  the tenant whose default should apply when
     *                  {@code override} is null / blank; must be non-null
     *                  for the fallback path.
     * @param override  optional explicit ISO-4217 code from the request.
     */
    public Mono<String> resolve(UUID tenantId, String override) {
        if (override != null && !override.isBlank()) {
            return Mono.just(override.trim().toUpperCase(Locale.ROOT));
        }
        if (tenantId == null) return Mono.just(PLATFORM_DEFAULT);
        return getDefaultCurrencyCode(tenantId);
    }

    /**
     * Looks up the tenant's {@code is_default=TRUE} currency. Reused by
     * cross-service consumers (Phase 5+) that need the reporting default
     * without the override-precedence branch.
     */
    public Mono<String> getDefaultCurrencyCode(UUID tenantId) {
        if (tenantId == null) return Mono.just(PLATFORM_DEFAULT);
        return databaseClient.sql("""
                SELECT currency_code FROM public.tenant_currency_config
                 WHERE tenant_id = :tid AND is_default = TRUE
                 LIMIT 1
                """)
                .bind("tid", tenantId)
                .map((row, meta) -> row.get("currency_code", String.class))
                .one()
                .defaultIfEmpty(PLATFORM_DEFAULT)
                .onErrorResume(err -> {
                    log.warn("[reporting-currency] default lookup failed for tenant {}: {} — falling back to {}",
                            tenantId, err.getMessage(), PLATFORM_DEFAULT);
                    return Mono.just(PLATFORM_DEFAULT);
                });
    }
}

package com.medfund.shared.currency;

import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Service-layer guard that rejects monetary writes whose {@code currency_code}
 * is not allowed for the tenant or does not match the parent scheme. The
 * implementation lives in tenancy-service (which owns currency state); other
 * services consume it via a Reactive REST client with Redis-cached results.
 */
public interface CurrencyValidator {

    /**
     * Asserts that {@code code} appears in {@code public.tenant_currency_config}
     * for {@code tenantId} with {@code is_active = TRUE}.
     */
    Mono<Void> requireAllowedForTenant(UUID tenantId, String code);

    /**
     * Asserts that {@code code} equals the parent scheme's {@code currency_code}.
     * Used when inserting a {@code scheme_benefit}, {@code age_group}, or
     * {@code contribution} row.
     */
    Mono<Void> requireMatchesScheme(UUID schemeId, String code);

    /**
     * Returns the tenant's default {@code currency_code}.
     */
    Mono<String> tenantDefault(UUID tenantId);

    /**
     * Thrown when a currency_code fails validation. Mapped to HTTP 400 by the
     * shared exception handler.
     */
    final class CurrencyNotAllowedException extends RuntimeException {
        public CurrencyNotAllowedException(String message) {
            super(message);
        }
    }
}

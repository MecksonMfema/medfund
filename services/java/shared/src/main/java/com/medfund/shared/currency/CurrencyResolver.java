package com.medfund.shared.currency;

import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Resolves the appropriate currency_code for a write that did not specify one.
 * Replaces the hard-coded {@code "USD"} fallbacks scattered through DTOs and
 * services. Implementations look up the tenant's default currency (and, where
 * applicable, the parent scheme's currency) via the tenancy-service.
 */
public interface CurrencyResolver {

    /**
     * Returns the requested code if non-null/non-blank, else the tenant's
     * default currency.
     */
    Mono<String> resolveOrDefault(UUID tenantId, String requested);

    /**
     * Returns the requested code if non-null/non-blank, else the parent scheme's
     * currency. Use for child entities (benefit, age-group, contribution) where
     * inheritance from the scheme is the natural fallback.
     */
    Mono<String> resolveOrSchemeCurrency(UUID schemeId, String requested);
}

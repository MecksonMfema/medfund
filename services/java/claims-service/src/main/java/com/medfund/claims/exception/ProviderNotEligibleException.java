package com.medfund.claims.exception;

/**
 * The referenced provider exists and the payload is well formed, but the
 * provider is not usable for this claim: either it has no active
 * {@code public.provider_tenants} row for the submitting tenant, or the
 * claim's insurance line is missing from its
 * {@code public.provider_insurance_lines} tags.
 *
 * <p>Mapped to 422, not 400: nothing about the request is malformed, so the
 * operator's fix is an administrative one (link the provider, or add the line
 * tag) rather than a correction to what they typed. The message names the
 * super-admin endpoint that resolves it.
 */
public class ProviderNotEligibleException extends RuntimeException {

    public ProviderNotEligibleException(String message) {
        super(message);
    }
}

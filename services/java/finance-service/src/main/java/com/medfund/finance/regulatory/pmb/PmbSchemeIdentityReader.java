package com.medfund.finance.regulatory.pmb;

import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * SPI for resolving the (scheme name, registration number) tuple that
 * lands in the PMB Spend report's meta section. Split from
 * {@link RealPmbSpendRawDataProvider} so a tenant that ships a curated
 * regulator-id table (or a JSON settings blob) can register its own
 * {@code @Primary @Component} without rewriting the provider.
 *
 * <p>Mirrors the AML pattern —
 * {@code com.medfund.finance.regulatory.aml.AmlFilingIdentityReader}.
 */
public interface PmbSchemeIdentityReader {

    Mono<SchemeIdentity> load(UUID tenantId);

    /** Immutable value carrying the two meta cells the report needs. */
    record SchemeIdentity(String name, String registrationNumber) {

        public static SchemeIdentity empty() {
            return new SchemeIdentity(null, null);
        }
    }
}

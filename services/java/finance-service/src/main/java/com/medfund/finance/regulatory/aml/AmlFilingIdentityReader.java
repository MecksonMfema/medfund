package com.medfund.finance.regulatory.aml;

import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * SPI for looking up per-tenant filing identity — the {@code reportingEntityName}
 * and {@code regulatorReference} written into the meta section of both the
 * periodic {@code AML_STR} summary (Phase 25) and per-STR filings (Phase 26).
 *
 * <p>The production {@link DefaultAmlFilingIdentityReader} reads
 * {@code tenants.name} + a placeholder regulator reference. Consumers can
 * override with a bean that pulls a curated per-tenant regulator id from a
 * dedicated tenant-config table once compliance operations require it.
 * The stub keeps tests hermetic.
 */
public interface AmlFilingIdentityReader {

    Mono<AmlFilingIdentity> load(UUID tenantId);

    /** Value tuple returned by the reader. Both fields nullable-safe. */
    record AmlFilingIdentity(String reportingEntityName, String regulatorReference) {

        public static AmlFilingIdentity empty() {
            return new AmlFilingIdentity(null, null);
        }
    }
}

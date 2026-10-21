package com.medfund.finance.regulatory.tax.vat;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Default {@link VatRateReader} — always yields {@link VatRates#zero()}
 * so a submission against an un-integrated tenant renders an
 * obviously-empty return.
 *
 * <p>A later sub-phase replaces this with a concrete
 * {@code R2dbcTenantVatRateReader} that queries
 * {@code public.tenant_tax_config}. Mark that replacement
 * {@code @Primary} to demote the stub.
 */
@Slf4j
@Component
public class StubVatRateReader implements VatRateReader {

    @Override
    public Mono<VatRates> resolve(UUID tenantId, String countryCode, String currency, LocalDate effectiveDate) {
        log.warn("[vat-rate] StubVatRateReader active — tenant={} country={} currency={} asOf={}"
                        + ". Real tenant_tax_config integration is deferred.",
                tenantId, countryCode, currency, effectiveDate);
        return Mono.just(VatRates.zero());
    }
}

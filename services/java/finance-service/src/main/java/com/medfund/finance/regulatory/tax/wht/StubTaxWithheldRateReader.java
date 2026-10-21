package com.medfund.finance.regulatory.tax.wht;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Default {@link TaxWithheldRateReader} — always yields
 * {@link TaxWithheldRates#zero()}. Replaced in a later sub-phase by a
 * concrete R2DBC reader that queries {@code public.tenant_tax_config}
 * where {@code tax_type='WITHHOLDING'}.
 */
@Slf4j
@Component
public class StubTaxWithheldRateReader implements TaxWithheldRateReader {

    @Override
    public Mono<TaxWithheldRates> resolve(UUID tenantId, String countryCode, String currency, LocalDate effectiveDate) {
        log.warn("[wht-rate] StubTaxWithheldRateReader active — tenant={} country={} currency={} asOf={}"
                        + ". Real tenant_tax_config integration is deferred.",
                tenantId, countryCode, currency, effectiveDate);
        return Mono.just(TaxWithheldRates.zero());
    }
}

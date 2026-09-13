package com.medfund.finance.regulatory.pmb;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Default {@link PmbSchemeIdentityReader}. Reads {@code tenants.name}
 * from {@code public.tenants} and returns a placeholder
 * {@code registrationNumber} until a curated per-tenant regulator-id
 * column ships. A future per-tenant compliance override supersedes this
 * by declaring itself {@code @Primary}.
 *
 * <p>Same story as {@code DefaultAmlFilingIdentityReader}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultPmbSchemeIdentityReader implements PmbSchemeIdentityReader {

    static final String PLACEHOLDER_REGISTRATION_NUMBER = "REG-REF-NOT-YET-CONFIGURED";

    private final DatabaseClient databaseClient;

    @Override
    public Mono<SchemeIdentity> load(UUID tenantId) {
        if (tenantId == null) {
            return Mono.just(new SchemeIdentity(null, PLACEHOLDER_REGISTRATION_NUMBER));
        }
        return databaseClient.sql("SELECT name FROM public.tenants WHERE id = :id")
                .bind("id", tenantId)
                .map((row, meta) -> row.get("name", String.class))
                .one()
                .map(name -> new SchemeIdentity(name, PLACEHOLDER_REGISTRATION_NUMBER))
                .defaultIfEmpty(new SchemeIdentity(null, PLACEHOLDER_REGISTRATION_NUMBER))
                .onErrorResume(err -> {
                    log.warn("[pmb-identity] tenant name lookup failed for {}: {}",
                            tenantId, err.getMessage());
                    return Mono.just(new SchemeIdentity(null, PLACEHOLDER_REGISTRATION_NUMBER));
                });
    }
}

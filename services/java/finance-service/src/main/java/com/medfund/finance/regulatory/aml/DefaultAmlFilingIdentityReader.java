package com.medfund.finance.regulatory.aml;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Default {@link AmlFilingIdentityReader}. Reads {@code tenants.name} from
 * {@code public.tenants} for the {@code reportingEntityName}, and returns
 * a placeholder {@code regulatorReference} until a curated per-tenant
 * regulator-id column ships. A future per-tenant compliance override can
 * supersede this by declaring itself {@code @Primary}.
 * (@ConditionalOnMissingBean on a @Component self-excludes at scan time —
 *  the guard has to live on a @Bean method in a @Configuration class.)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultAmlFilingIdentityReader implements AmlFilingIdentityReader {

    private static final String PLACEHOLDER_REG_REF = "REG-REF-NOT-YET-CONFIGURED";

    private final DatabaseClient databaseClient;

    @Override
    public Mono<AmlFilingIdentity> load(UUID tenantId) {
        if (tenantId == null) return Mono.just(AmlFilingIdentity.empty());
        return databaseClient.sql("SELECT name FROM public.tenants WHERE id = :id")
                .bind("id", tenantId)
                .map((row, meta) -> row.get("name", String.class))
                .one()
                .map(name -> new AmlFilingIdentity(name, PLACEHOLDER_REG_REF))
                .defaultIfEmpty(new AmlFilingIdentity(null, PLACEHOLDER_REG_REF))
                .onErrorResume(err -> {
                    log.warn("[aml-identity] tenant name lookup failed for {}: {} — using empty",
                            tenantId, err.getMessage());
                    return Mono.just(AmlFilingIdentity.empty());
                });
    }
}

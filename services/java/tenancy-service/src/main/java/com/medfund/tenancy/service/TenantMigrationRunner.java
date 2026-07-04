package com.medfund.tenancy.service;

import io.r2dbc.spi.ConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Backfill runner — on tenancy-service boot, applies any pending
 * {@code db/migration/tenant} migrations to every schema already
 * listed in {@code public.tenants}.
 *
 * <p>Without this hook, a new tenant migration file only reaches the
 * DB when a brand-new tenant is provisioned (via
 * {@link SchemaProvisioningService#provisionSchema(String)}). Existing
 * tenants would be stuck at whatever version they were provisioned
 * on and their schema would drift from the codebase. Running Flyway
 * on boot for each tenant closes the gap — Flyway is idempotent, so
 * subsequent boots are near-free when nothing is pending.
 *
 * <p>Boot order matters: this runs AFTER Spring Boot's own Flyway
 * autoconfig (which applies {@code db/migration/public} — and, in the
 * dev config, also {@code db/migration/tenant} against the public
 * schema, harmlessly recording them). CommandLineRunner fires after
 * the ApplicationContext is fully initialised, which is exactly when
 * we want to see the tenants list and iterate.
 */
@Component
public class TenantMigrationRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(TenantMigrationRunner.class);

    private final ConnectionFactory connectionFactory;
    private final SchemaProvisioningService provisioning;

    public TenantMigrationRunner(ConnectionFactory connectionFactory,
                                  SchemaProvisioningService provisioning) {
        this.connectionFactory = connectionFactory;
        this.provisioning = provisioning;
    }

    @Override
    public void run(String... args) {
        log.info("Running pending tenant migrations across all provisioned schemas");
        try {
            long count = tenantSchemas()
                    .flatMap(schema -> provisioning.migrateTenantSchema(schema)
                            .doOnSuccess(v -> log.info("Migrated tenant schema: {}", schema))
                            .onErrorResume(err -> {
                                // Log-and-continue so one bad tenant doesn't stall
                                // the whole app boot — the operator will see the
                                // error in the log and can investigate.
                                log.error("Migration failed for schema {}: {}", schema, err.getMessage(), err);
                                return Mono.empty();
                            }))
                    .count()
                    .blockOptional()
                    .orElse(0L);
            log.info("Tenant migration sweep complete — {} schemas processed", count);
        } catch (Exception e) {
            log.error("Tenant migration sweep failed: {}", e.getMessage(), e);
        }
    }

    private Flux<String> tenantSchemas() {
        return Mono.from(connectionFactory.create())
                .flatMapMany(conn -> Flux.from(conn.createStatement(
                                "SELECT schema_name FROM public.tenants WHERE schema_name IS NOT NULL")
                                .execute())
                        .flatMap(result -> result.map((row, meta) -> row.get("schema_name", String.class)))
                        .doFinally(s -> conn.close()));
    }
}

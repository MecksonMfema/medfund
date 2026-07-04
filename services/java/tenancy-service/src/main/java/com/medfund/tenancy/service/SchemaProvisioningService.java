package com.medfund.tenancy.service;

import io.r2dbc.spi.ConnectionFactory;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Provisions a new PostgreSQL schema for a tenant and runs Flyway migrations.
 * Schema creation uses R2DBC; Flyway uses JDBC (blocking, run on boundedElastic).
 */
@Service
public class SchemaProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(SchemaProvisioningService.class);

    private final ConnectionFactory connectionFactory;

    @Value("${spring.flyway.url}")
    private String flywayJdbcUrl;

    @Value("${spring.flyway.user}")
    private String flywayUser;

    @Value("${spring.flyway.password}")
    private String flywayPassword;

    public SchemaProvisioningService(ConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    public Mono<Void> provisionSchema(String schemaName) {
        return createSchema(schemaName)
                .then(runMigrations(schemaName))
                .then(provisionTenantRole(schemaName));
    }

    private Mono<Void> createSchema(String schemaName) {
        return Mono.from(connectionFactory.create())
                .flatMap(conn ->
                        Mono.from(conn.createStatement("CREATE SCHEMA IF NOT EXISTS " + schemaName).execute())
                                .doOnSuccess(r -> log.info("Created schema: {}", schemaName))
                                .doFinally(s -> conn.close())
                                .then()
                );
    }

    /**
     * Run the shared role-provisioning function from {@code V117}. Creates
     * the {@code <schemaName>_role} (if missing), grants it MEMBER to the
     * connection-pool user so the connection factory can SET ROLE into it,
     * and lays down the schema + whitelisted public grants. Idempotent —
     * safe to call on an existing tenant.
     */
    private Mono<Void> provisionTenantRole(String schemaName) {
        return Mono.from(connectionFactory.create())
                .flatMap(conn -> Mono.from(conn.createStatement(
                                "SELECT public.provision_tenant_role($1)")
                                .bind("$1", schemaName)
                                .execute())
                        .doOnSuccess(r -> log.info("Provisioned tenant role for schema: {}", schemaName))
                        .doFinally(s -> conn.close())
                        .then());
    }

    private Mono<Void> runMigrations(String schemaName) {
        return migrateTenantSchema(schemaName);
    }

    /**
     * Package-visible entry point for TenantMigrationRunner. Runs the
     * {@code db/migration/tenant} Flyway locations against the supplied
     * schema. Idempotent — Flyway skips migrations already applied to
     * that schema's own {@code flyway_schema_history} table, so calling
     * on boot for every tenant is cheap once the tenants are current.
     */
    Mono<Void> migrateTenantSchema(String schemaName) {
        return Mono.fromRunnable(() -> {
            Flyway flyway = Flyway.configure()
                    .dataSource(flywayJdbcUrl, flywayUser, flywayPassword)
                    .schemas(schemaName)
                    .locations("classpath:db/migration/tenant")
                    .baselineOnMigrate(true)
                    // Legacy tenant schemas had ad-hoc migrations recorded in
                    // application.yml order; allow out-of-order so a new
                    // Vxx that lands after a Vxxxx from public/ still runs.
                    .outOfOrder(true)
                    .load();
            // Repair first — same rationale as FlywayConfig for the public
            // schema. If a prior tenant Flyway run failed (bad SQL, DB blip)
            // the failed entry is deleted and applied entries have their
            // checksums realigned. Cheap when there's nothing to fix.
            try {
                flyway.repair();
            } catch (Exception e) {
                log.warn("Flyway repair() for {} reported: {} — continuing to migrate()",
                        schemaName, e.getMessage());
            }
            flyway.migrate();
            log.info("Flyway migrations complete for schema: {}", schemaName);
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }
}

package com.medfund.shared.testfixtures;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Postgres-only test base with a <em>dedicated</em> container that no other
 * test class shares.
 *
 * <p>Extend this when a test class needs its own migration set but every
 * existing Postgres base is already claimed by another class — otherwise two
 * classes pointing Flyway at different {@code db/...-migration} paths on the
 * same container collide: whichever boots second fails
 * {@code FlywayValidateException} on the version-1 checksum mismatch (the
 * classic {@code group-create-migration} vs {@code group-number-migration}
 * symptom in user-service and {@code charge-preview-migration} vs
 * {@code test-migration} in contributions-service).
 *
 * <p>One class per dedicated base. If a second class ever extends this, they
 * share the container and the isolation is gone — add another base instead.
 */
public abstract class AbstractDedicatedPostgresIntegrationTest {

    protected static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("medfund")
            .withUsername("medfund")
            .withPassword("medfund");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url", () -> String.format(
            "r2dbc:postgresql://%s:%d/%s",
            POSTGRES.getHost(), POSTGRES.getFirstMappedPort(), POSTGRES.getDatabaseName()));
        registry.add("spring.r2dbc.username", POSTGRES::getUsername);
        registry.add("spring.r2dbc.password", POSTGRES::getPassword);

        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
    }
}

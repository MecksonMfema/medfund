package com.medfund.shared.testfixtures;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for Spring Boot integration tests that need a real Postgres
 * instance. The container is {@code static} and started once per JVM so every
 * test class in the same Gradle run shares it.
 *
 * <p>Subclasses inherit two Spring properties:
 * <ul>
 *   <li>{@code spring.r2dbc.url} — reactive driver pointed at the container</li>
 *   <li>{@code spring.flyway.url}/{@code .user}/{@code .password} — JDBC URL for the
 *       Flyway migrator (Flyway uses blocking JDBC, not R2DBC)</li>
 * </ul>
 *
 * <p>Per CLAUDE.md every query is tenant-scoped; integration tests should
 * combine this base with {@code TenantTestContext.withTenant(...)} so the
 * reactor context carries a tenant ID through the call chain.
 *
 * <p>Subclasses that also need Kafka should extend
 * {@link AbstractIntegrationTest} instead — it combines both.
 */
public abstract class AbstractPostgresIntegrationTest {

    protected static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("medfund")
            .withUsername("medfund")
            .withPassword("medfund");

    static {
        // Started once per JVM, NOT per test class — the @Testcontainers
        // extension would otherwise stop the container at the end of each class
        // and invalidate any cached Spring context pool (see AbstractIntegrationTest).
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

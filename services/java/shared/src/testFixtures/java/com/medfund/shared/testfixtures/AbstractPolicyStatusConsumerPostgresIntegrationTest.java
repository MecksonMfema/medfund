package com.medfund.shared.testfixtures;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Dedicated Postgres container for the Phase 13 §B Phase 6
 * {@code EarningScheduleClosureLifecycleIT}. Sibling of
 * {@link AbstractDedicatedPostgresIntegrationTest} (which is claimed by
 * {@code BillingServiceChargePreviewIT}) — one class per dedicated base
 * so two ITs pointing Flyway at different {@code db/...-migration} paths
 * never collide on the version-1 checksum.
 *
 * <p>See the sibling class's comment for the full rationale.
 */
public abstract class AbstractPolicyStatusConsumerPostgresIntegrationTest {

    protected static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("medfund")
            .withUsername("medfund")
            .withPassword("medfund")
            .withCommand("postgres", "-c", "max_connections=300", "-c", "shared_buffers=64MB");

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

package com.medfund.tenancy.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Flyway-only integration test: boots a fresh Postgres via Testcontainers,
 * runs the tenant migrations end-to-end, and asserts that V042 / V043 / V044
 * landed the columns downstream code depends on.
 *
 * <p>Guards the plan's most fragile surface: those three migrations moved
 * suspend_reason, the scheduled trio, and the reminder knobs into shape —
 * any of them silently reverting (an errant rename, a dropped column) would
 * break the arrears sweep and daily roll at runtime, not in a compile check.
 */
@Testcontainers
class TenantMigrationFlywayIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("medfund_migration_it")
            .withUsername("medfund")
            .withPassword("medfund");

    @Test
    void tenantMigrations_landAllExpectedColumns() throws Exception {
        Flyway flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration/tenant")
                .schemas("tenant_it")
                .createSchemas(true)
                .load();
        flyway.migrate();

        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {

            // V042 — scheduled trio on members + groups.
            assertColumns(conn, "tenant_it", "members", List.of(
                    "scheduled_status",
                    "scheduled_status_effective_from",
                    "scheduled_status_reason"));
            assertColumns(conn, "tenant_it", "groups", List.of(
                    "scheduled_status",
                    "scheduled_status_effective_from",
                    "scheduled_status_reason"));

            // V042 — dunning_config column rename (was write_off_days).
            assertColumns(conn, "tenant_it", "dunning_config", List.of("deactivation_days"));
            assertNoColumn(conn, "tenant_it", "dunning_config", "write_off_days");

            // V043 — suspend_reason on members + groups.
            assertColumns(conn, "tenant_it", "members", List.of("suspend_reason"));
            assertColumns(conn, "tenant_it", "groups", List.of("suspend_reason"));

            // V044 — arrears reminder knobs on dunning_config.
            assertColumns(conn, "tenant_it", "dunning_config", List.of(
                    "auto_remind",
                    "reminder_lead_days",
                    "reminder_interval_days",
                    "reminder_continue_past_suspension"));
        }
    }

    private static void assertColumns(Connection conn, String schema, String table,
                                       List<String> expected) throws Exception {
        Set<String> present = new HashSet<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT column_name FROM information_schema.columns " +
                " WHERE table_schema = ? AND table_name = ?")) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    present.add(rs.getString(1));
                }
            }
        }
        assertThat(present).as("%s.%s must include %s", schema, table, expected)
                .containsAll(expected);
    }

    private static void assertNoColumn(Connection conn, String schema, String table,
                                        String column) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM information_schema.columns " +
                " WHERE table_schema = ? AND table_name = ? AND column_name = ?")) {
            ps.setString(1, schema);
            ps.setString(2, table);
            ps.setString(3, column);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next())
                        .as("%s.%s must no longer have %s (renamed in V042)", schema, table, column)
                        .isFalse();
            }
        }
    }
}

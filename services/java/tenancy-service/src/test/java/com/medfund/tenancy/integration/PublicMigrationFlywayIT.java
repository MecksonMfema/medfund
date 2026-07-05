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
 * Companion to {@link TenantMigrationFlywayIT} — this one runs the
 * <b>public</b> migration set end-to-end against a fresh Postgres. It
 * catches the class of failure the unit + slice ITs can't: a syntax
 * error in a migration file. Slice ITs use their own stripped-down
 * test-migration folders, so a broken production V125 (say, {@code ||}
 * inside a {@code COMMENT ON COLUMN} that Postgres won't accept)
 * doesn't surface anywhere in the test suite until it fails on a
 * real bootRun. This IT closes that gap.
 *
 * <p>Deliberately assertion-light on individual columns — the tenant
 * IT already handles that shape. The value here is proving every
 * script parses and runs on a real Postgres in one Flyway pass. If a
 * new migration adds a syntax error, {@code flyway.migrate()} throws
 * before any column assertion is reached and the test fails loudly.
 */
@Testcontainers
class PublicMigrationFlywayIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("medfund_public_migration_it")
            .withUsername("medfund")
            .withPassword("medfund");

    @Test
    void publicMigrations_applyCleanly_andLandLatestExpectedColumns() throws Exception {
        Flyway flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration/public")
                .schemas("public")
                .load();
        // If any script has a syntax error, this throws with the exact
        // filename + line number — that's the failure signal we want.
        flyway.migrate();

        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {

            // V125 — tenant group-number config knobs. Anchor a spot
            // check here so a well-meaning rewrite that changed the
            // column names would fail the assertion and the operator
            // sees the specific column at fault. If new public
            // migrations add more columns, extend this list.
            assertColumns(conn, "public", "tenants", List.of(
                    "group_number_prefix",
                    "group_number_suffix",
                    "group_number_random_length"));
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
}

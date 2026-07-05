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

            // V046 — dependant deactivation column exists.
            assertColumns(conn, "tenant_it", "dependants",
                    List.of("deactivation_effective_date"));
        }
    }

    /**
     * V046 backfill: rows already at status='removed' must be migrated
     * to status='deactivated' with deactivation_effective_date =
     * updated_at::date. The intermediate step is fragile — a subtle
     * typo in the SQL (missing WHERE, wrong column) would either miss
     * the rows entirely or wipe active dependants. Boot a fresh
     * container, stop at V045 so we can seed the 'removed' fixture on
     * the pre-V046 schema, then run V046 and inspect the result.
     */
    @Test
    void v046_backfillsRemovedRowsToDeactivated_withEffectiveDateFromUpdatedAt() throws Exception {
        String schema = "tenant_v046_backfill_it";

        // Stage 1 — migrate up to V045 so 'dependants' exists but the
        // deactivation column doesn't.
        Flyway upToV045 = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration/tenant")
                .schemas(schema)
                .createSchemas(true)
                .target("45")
                .load();
        upToV045.migrate();

        // Seed a 'removed' row + a control 'active' row (to prove the
        // WHERE clause is scoped correctly and doesn't clobber active
        // dependants).
        java.time.LocalDateTime historicUpdatedAt =
                java.time.LocalDateTime.of(2026, 3, 12, 9, 30);
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {

            // dependants requires a member. Insert the tree minimally.
            // Seed a scheme first — members.scheme_id is NOT NULL.
            String schemeId;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO " + schema + ".schemes " +
                    "  (id, name, insurance_line, status, currency_code, effective_date) " +
                    "  VALUES (gen_random_uuid(), 'IT Scheme', 'HEALTH', 'active', 'USD', CURRENT_DATE) " +
                    "  RETURNING id")) {
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    schemeId = rs.getString(1);
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO " + schema + ".members " +
                    "  (id, first_name, last_name, member_number, date_of_birth, national_id, gender, email," +
                    "   scheme_id, status, enrollment_date, created_at, updated_at) " +
                    "  VALUES (gen_random_uuid(), 'Parent', 'One', 'MBR-100001', '1980-01-01', '63-1', 'male', 'p@e', " +
                    "          ?::uuid, 'active', date_trunc('month', CURRENT_DATE)::date, now(), now()) " +
                    "  RETURNING id")) {
                ps.setString(1, schemeId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    String memberId = rs.getString(1);

                    try (PreparedStatement dep = conn.prepareStatement(
                            "INSERT INTO " + schema + ".dependants " +
                            "  (id, member_id, first_name, last_name, date_of_birth, gender, relationship, national_id," +
                            "   status, created_at, updated_at) " +
                            "  VALUES (gen_random_uuid(), ?::uuid, 'Rem', 'Oved', '2015-05-01', 'female', 'child', '63-r-01', 'removed', now(), ?), " +
                            "         (gen_random_uuid(), ?::uuid, 'Act', 'Ive', '2018-03-01', 'male',   'child', '63-a-02', 'active',  now(), now())")) {
                        dep.setString(1, memberId);
                        dep.setTimestamp(2, java.sql.Timestamp.valueOf(historicUpdatedAt));
                        dep.setString(3, memberId);
                        dep.executeUpdate();
                    }
                }
            }
        }

        // Stage 2 — run migrations all the way. V046 fires the backfill.
        Flyway toLatest = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration/tenant")
                .schemas(schema)
                .load();
        toLatest.migrate();

        // Assert: removed → deactivated with effective_date = updated_at::date.
        // Active row untouched.
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT status, deactivation_effective_date " +
                    "  FROM " + schema + ".dependants WHERE first_name = 'Rem'")) {
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("status")).isEqualTo("deactivated");
                    assertThat(rs.getDate("deactivation_effective_date"))
                            .as("Effective date backfilled from updated_at::date")
                            .isEqualTo(java.sql.Date.valueOf(historicUpdatedAt.toLocalDate()));
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT status, deactivation_effective_date " +
                    "  FROM " + schema + ".dependants WHERE first_name = 'Act'")) {
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("status"))
                            .as("Active dependants must NOT be touched by the backfill")
                            .isEqualTo("active");
                    assertThat(rs.getDate("deactivation_effective_date"))
                            .as("Active rows keep effective_date null")
                            .isNull();
                }
            }
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

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

            // V102 — six policy tables carry the underwriting columns.
            List<String> underwritingCols = List.of(
                    "written_premium", "written_premium_currency", "bound_at",
                    "renewed_from_policy_id", "portfolio_id", "cohort_id");
            for (String table : List.of("life_policies", "funeral_policies",
                    "disability_policies", "vehicles", "properties")) {
                assertColumns(conn, "tenant_it", table, underwritingCols);
                assertColumns(conn, "tenant_it", table, List.of("coverage_start", "coverage_end"));
            }
            // TravelPolicy reuses trip_start_date/trip_end_date; no coverage_start/end.
            assertColumns(conn, "tenant_it", "travel_policies", underwritingCols);

            // V102 — contribution + scheme portfolio hooks.
            assertColumns(conn, "tenant_it", "contributions", List.of("portfolio_id", "cohort_id"));
            assertColumns(conn, "tenant_it", "schemes", List.of("default_portfolio_id"));

            // V107 / V108 / V109 — new tables exist with their key columns.
            assertColumns(conn, "tenant_it", "ifrs17_portfolio",
                    List.of("name", "insurance_line", "is_active"));
            assertColumns(conn, "tenant_it", "ifrs17_cohort",
                    List.of("portfolio_id", "cohort_year", "cohort_type", "name"));
            assertColumns(conn, "tenant_it", "earning_schedule",
                    List.of("policy_id", "policy_source", "period_start", "period_end",
                            "written_amount", "earned_at_period_end", "is_endorsement",
                            "endorsement_id", "earning_method"));
            assertColumns(conn, "tenant_it", "earning_schedule_run",
                    List.of("tenant_id", "run_kind", "status", "last_processed_policy_id"));

            // V110 — endorsement table with four-eyes lifecycle columns.
            assertColumns(conn, "tenant_it", "endorsement",
                    List.of("reference", "policy_id", "policy_source", "insurance_line",
                            "change_type", "effective_from", "premium_delta", "currency_code",
                            "reason", "status",
                            "draft_actor_id", "draft_actor_email", "draft_at",
                            "approve_actor_id", "approve_actor_email", "approve_at",
                            "commit_actor_id", "commit_actor_email", "commit_at",
                            "voided_reason", "voided_at"));

            // V107 — MISC portfolio seeded on every fresh tenant.
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT insurance_line FROM tenant_it.ifrs17_portfolio WHERE name = 'MISC'")) {
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).as("MISC portfolio seeded by V107").isTrue();
                    assertThat(rs.getString("insurance_line"))
                            .as("MISC portfolio insurance_line is NULL (catchall)")
                            .isNull();
                }
            }

            // V108 — MISC-YYYY-DEFAULT cohort seeded and attached to MISC.
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT c.cohort_type FROM tenant_it.ifrs17_cohort c " +
                    "  JOIN tenant_it.ifrs17_portfolio p ON p.id = c.portfolio_id " +
                    " WHERE p.name = 'MISC' AND c.name LIKE 'MISC-%-DEFAULT'")) {
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).as("MISC-YYYY-DEFAULT cohort seeded by V108").isTrue();
                    assertThat(rs.getString("cohort_type")).isEqualTo("NON_ONEROUS");
                }
            }
        }
    }

    /**
     * V102 backfill: legacy policy rows (created before Phase 12) get
     * bound_at/coverage_start/coverage_end filled in from created_at,
     * and status flipped to 'legacy_no_premium' when written_premium is
     * still null. V107 + V108 then point them at MISC + MISC-YYYY-DEFAULT.
     * Guard by staging at V101, seeding a legacy vehicle row, then
     * running V102-V109 and inspecting the result.
     */
    @Test
    void v102_backfillsLegacyPolicies_toLegacyNoPremiumWithMiscPortfolio() throws Exception {
        String schema = "tenant_v102_backfill_it";

        // Stage 1 — migrate up to V101 so policy tables exist without the
        // Phase 12 columns. Seed one vehicle row so we can verify backfill.
        Flyway upToV101 = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration/tenant")
                .schemas(schema)
                .createSchemas(true)
                .target("101")
                .load();
        upToV101.migrate();

        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            String schemeId;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO " + schema + ".schemes " +
                    "  (id, name, insurance_line, status, currency_code, effective_date) " +
                    "  VALUES (gen_random_uuid(), 'IT Motor Scheme', 'VEHICLE', 'active', 'USD', CURRENT_DATE) " +
                    "  RETURNING id")) {
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    schemeId = rs.getString(1);
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO " + schema + ".vehicles " +
                    "  (id, scheme_id, registration_number, make, model, year, vehicle_value, status) " +
                    "  VALUES (gen_random_uuid(), ?::uuid, 'ABC-123', 'Toyota', 'Corolla', 2020, 10000, 'active')")) {
                ps.setString(1, schemeId);
                ps.executeUpdate();
            }
        }

        // Stage 2 — run migrations to head. V102 backfills, V107/V108 point to MISC.
        Flyway toLatest = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration/tenant")
                .schemas(schema)
                .load();
        toLatest.migrate();

        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT v.status, v.bound_at, v.coverage_start, v.coverage_end, v.written_premium," +
                    "       p.name AS portfolio_name, c.name AS cohort_name " +
                    "  FROM " + schema + ".vehicles v " +
                    "  LEFT JOIN " + schema + ".ifrs17_portfolio p ON p.id = v.portfolio_id " +
                    "  LEFT JOIN " + schema + ".ifrs17_cohort c    ON c.id = v.cohort_id " +
                    " WHERE registration_number = 'ABC-123'")) {
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("status"))
                            .as("Legacy vehicle without written_premium flipped to 'legacy_no_premium'")
                            .isEqualTo("legacy_no_premium");
                    assertThat(rs.getTimestamp("bound_at"))
                            .as("bound_at backfilled from created_at").isNotNull();
                    assertThat(rs.getDate("coverage_start"))
                            .as("coverage_start backfilled from created_at::date").isNotNull();
                    assertThat(rs.getDate("coverage_end"))
                            .as("coverage_end backfilled to created_at + 1 year").isNotNull();
                    assertThat(rs.getBigDecimal("written_premium"))
                            .as("written_premium stays NULL for legacy rows").isNull();
                    assertThat(rs.getString("portfolio_name"))
                            .as("legacy vehicle attached to MISC portfolio").isEqualTo("MISC");
                    assertThat(rs.getString("cohort_name"))
                            .as("legacy vehicle attached to MISC-YYYY-DEFAULT cohort")
                            .startsWith("MISC-").endsWith("-DEFAULT");
                }
            }
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

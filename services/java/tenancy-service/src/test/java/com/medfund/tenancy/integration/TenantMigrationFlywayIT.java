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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
                            "endorsement_id", "earning_method",
                            "closure_ref", "is_closure"));  // V115
            assertColumns(conn, "tenant_it", "earning_schedule_run",
                    List.of("tenant_id", "run_kind", "status", "last_processed_policy_id",
                            "contrib_presence_refresh_at"));  // V114

            // V114 — member_contribution_presence matview + its UNIQUE index.
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT 1 FROM pg_matviews " +
                    " WHERE schemaname = 'tenant_it' AND matviewname = 'member_contribution_presence'")) {
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next())
                            .as("member_contribution_presence matview must exist")
                            .isTrue();
                }
            }
            assertIndexExists(conn, "tenant_it", "ux_member_contribution_presence");

            // V110 — endorsement table with four-eyes lifecycle columns.
            assertColumns(conn, "tenant_it", "endorsement",
                    List.of("reference", "policy_id", "policy_source", "insurance_line",
                            "change_type", "effective_from", "premium_delta", "currency_code",
                            "reason", "status",
                            "draft_actor_id", "draft_actor_email", "draft_at",
                            "approve_actor_id", "approve_actor_email", "approve_at",
                            "commit_actor_id", "commit_actor_email", "commit_at",
                            "voided_reason", "voided_at"));

            // Phase 13 §A — V111 / V112 history tables + V113 provider.network_tier.
            assertColumns(conn, "tenant_it", "policy_status_history", List.of(
                    "policy_id", "policy_source", "from_status", "to_status",
                    "effective_at", "actor_id", "actor_email", "reason_code", "reason_note"));
            assertColumns(conn, "tenant_it", "member_status_history", List.of(
                    "member_id", "from_status", "to_status",
                    "effective_at", "actor_id", "actor_email", "reason_code", "reason_note"));
            assertColumns(conn, "tenant_it", "providers", List.of("network_tier"));

            // Phase 14 §A/B/D — V139 claim_reserve_history + V140 member death columns
            // + V141 actuarial_report_job.
            assertColumns(conn, "tenant_it", "claim_reserve_history", List.of(
                    "id", "claim_id", "reserved_amount", "effective_at",
                    "actor_id", "actor_email", "reason_note", "created_at"));
            assertIndexExists(conn, "tenant_it", "idx_crh_claim_time");

            assertColumns(conn, "tenant_it", "members", List.of("death_date", "cause_of_death"));

            assertColumns(conn, "tenant_it", "actuarial_report_job", List.of(
                    "job_id", "tenant_id", "report_key", "status",
                    "params_json", "params_hash", "result_json", "error_message",
                    "requested_at", "completed_at", "requested_by", "requested_by_email"));
            assertIndexExists(conn, "tenant_it", "idx_arj_lookup");
            assertIndexExists(conn, "tenant_it", "ux_arj_inflight");

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
     * Phase 13 §A shape guard: V111 + V112 + V113 land with their columns,
     * named CHECK constraints, and indexes intact. A silent rename or a
     * dropped constraint would break PolicyStatusTransitionService /
     * StatusTransitionRecorder writes at runtime, not at compile time.
     */
    @Test
    void tenantMigrations_v111_landsPolicyStatusHistoryTable() throws Exception {
        String schema = "tenant_v113_shape_it";

        Flyway flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration/tenant")
                .schemas(schema)
                .createSchemas(true)
                .load();
        flyway.migrate();

        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {

            assertColumns(conn, schema, "policy_status_history", List.of(
                    "id", "policy_id", "policy_source", "from_status", "to_status",
                    "effective_at", "actor_id", "actor_email", "reason_code",
                    "reason_note", "created_at"));
            assertColumns(conn, schema, "member_status_history", List.of(
                    "id", "member_id", "from_status", "to_status",
                    "effective_at", "actor_id", "actor_email", "reason_code",
                    "reason_note", "created_at"));
            assertColumns(conn, schema, "providers", List.of("network_tier"));

            // Named CHECK constraints from V111 / V112 / V113.
            assertConstraintExists(conn, schema, "policy_status_history", "chk_policy_status_history_source");
            assertConstraintExists(conn, schema, "member_status_history", "chk_member_status_history_reason");
            assertConstraintExists(conn, schema, "providers", "chk_providers_network_tier");

            // Indexes the report queries lean on.
            assertIndexExists(conn, schema, "ix_policy_status_history_policy_effective");
            assertIndexExists(conn, schema, "ix_policy_status_history_source_status_effective");
            assertIndexExists(conn, schema, "ix_member_status_history_member_effective");
            assertIndexExists(conn, schema, "ix_member_status_history_status_effective");
            assertIndexExists(conn, schema, "ix_providers_network_tier");
        }
    }

    /**
     * Phase 13 §B Phase 6 shape guard: V115 adds closure_ref + is_closure
     * columns to earning_schedule plus a partial index on closure_ref.
     * PolicyStatusChangedConsumer's idempotency lookup and the nightly
     * executor's frozen-row skip both hinge on these columns landing at
     * the right shape — a silent rename or missing partial index would
     * break either behaviour at runtime.
     */
    @Test
    void tenantMigrations_v115_landsEarningScheduleClosureColumns() throws Exception {
        String schema = "tenant_v115_shape_it";

        Flyway flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration/tenant")
                .schemas(schema)
                .createSchemas(true)
                .load();
        flyway.migrate();

        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {

            assertColumns(conn, schema, "earning_schedule", List.of("closure_ref", "is_closure"));
            assertIndexExists(conn, schema, "ix_earning_schedule_closure_ref");
            assertIndexExists(conn, schema, "ix_earning_schedule_closure_policy");

            // is_closure defaults FALSE so pre-Phase-13 rows stay in the nightly
            // executor scan and the "is_closure = FALSE" predicate is safe.
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT column_default FROM information_schema.columns " +
                    " WHERE table_schema = ? AND table_name = 'earning_schedule' " +
                    "   AND column_name = 'is_closure'")) {
                ps.setString(1, schema);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString(1))
                            .as("is_closure default keeps legacy rows inside the nightly scan")
                            .containsIgnoringCase("false");
                }
            }
        }
    }

    /**
     * Phase 13 §A backfill per L7: stage at V110, seed one policy per
     * annual-bind line plus three members in distinct terminal states,
     * then migrate to head and assert the history tables were seeded —
     * exactly one initial_backfill row per policy at bound_at, a second
     * row for the terminated member from termination_date, a second row
     * for the suspended member from updated_at carrying suspend_reason,
     * no orphan member rows, and providers defaulted to STANDARD.
     */
    @Test
    void v111_to_v113_backfill_seedsHistoryRowsFromCurrentState() throws Exception {
        String schema = "tenant_p13_backfill_it";
        String qualified = schema + ".";

        // Stage 1 — migrate up to V110 so the pre-Phase-13 schema exists.
        Flyway upToV110 = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration/tenant")
                .schemas(schema)
                .createSchemas(true)
                .target("110")
                .load();
        upToV110.migrate();

        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            conn.setAutoCommit(false);
            try {
                String schemeId;
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO " + qualified + "schemes " +
                        "  (id, name, insurance_line, status, currency_code, effective_date) " +
                        "  VALUES (gen_random_uuid(), 'Phase 13 IT Scheme', 'LIFE', 'active', 'USD', CURRENT_DATE) " +
                        "  RETURNING id")) {
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        schemeId = rs.getString(1);
                    }
                }

                // Three members: active / terminated-with-date / suspended-with-reason.
                // gender + national_id + email + scheme_id are NOT NULL per V026;
                // enrollment_date must be a 1st-of-month per V026.
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO " + qualified + "members " +
                        "  (member_number, first_name, last_name, date_of_birth, gender, national_id, email," +
                        "   scheme_id, status, enrollment_date) VALUES " +
                        "  ('P13-ACTIVE', 'Active', 'Member', '1980-01-01', 'male', 'P13-NAT-1', 'p13-active@it', ?::uuid, 'active',     DATE '2026-01-01'), " +
                        "  ('P13-TERM',   'Termed', 'Member', '1975-05-05', 'male', 'P13-NAT-2', 'p13-term@it',   ?::uuid, 'terminated', DATE '2026-01-01'), " +
                        "  ('P13-SUSP',   'Susp',   'Member', '1990-09-09', 'male', 'P13-NAT-3', 'p13-susp@it',   ?::uuid, 'suspended',  DATE '2026-01-01')")) {
                    for (int i = 1; i <= 3; i++) ps.setString(i, schemeId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE " + qualified + "members SET termination_date = DATE '2026-03-31' " +
                        " WHERE member_number = 'P13-TERM'")) {
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE " + qualified + "members " +
                        "   SET suspend_reason = 'arrears_non_payment', " +
                        "       updated_at     = TIMESTAMPTZ '2026-04-15 10:30:00+00' " +
                        " WHERE member_number = 'P13-SUSP'")) {
                    ps.executeUpdate();
                }

                String activeMemberId;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT id FROM " + qualified + "members WHERE member_number = 'P13-ACTIVE'")) {
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        activeMemberId = rs.getString(1);
                    }
                }

                // One policy per annual-bind line, all active with a known bound_at.
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO " + qualified + "life_policies " +
                        "  (scheme_id, insured_member_id, policy_number, sum_assured, term_months, status, " +
                        "   written_premium, written_premium_currency, bound_at, coverage_start, coverage_end) " +
                        "  VALUES (?::uuid, ?::uuid, 'P13-LIFE-1', 100000, 12, 'active', 1200, 'USD', " +
                        "          TIMESTAMPTZ '2026-01-15 08:00:00+00', DATE '2026-01-01', DATE '2026-12-31')")) {
                    ps.setString(1, schemeId);
                    ps.setString(2, activeMemberId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO " + qualified + "funeral_policies " +
                        "  (scheme_id, principal_member_id, policy_number, cover_amount, status, written_premium, bound_at) " +
                        "  VALUES (?::uuid, ?::uuid, 'P13-FUN-1', 15000, 'active', 300, TIMESTAMPTZ '2026-01-15 08:00:00+00')")) {
                    ps.setString(1, schemeId);
                    ps.setString(2, activeMemberId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO " + qualified + "disability_policies " +
                        "  (scheme_id, insured_member_id, policy_number, waiting_period_days, monthly_benefit, status, written_premium, bound_at) " +
                        "  VALUES (?::uuid, ?::uuid, 'P13-DIS-1', 30, 5000, 'active', 900, TIMESTAMPTZ '2026-01-15 08:00:00+00')")) {
                    ps.setString(1, schemeId);
                    ps.setString(2, activeMemberId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO " + qualified + "travel_policies " +
                        "  (scheme_id, traveler_member_id, policy_number, trip_start_date, trip_end_date, status, written_premium, bound_at) " +
                        "  VALUES (?::uuid, ?::uuid, 'P13-TRV-1', DATE '2026-02-01', DATE '2026-02-14', 'active', 250, TIMESTAMPTZ '2026-01-15 08:00:00+00')")) {
                    ps.setString(1, schemeId);
                    ps.setString(2, activeMemberId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO " + qualified + "vehicles " +
                        "  (scheme_id, registration_number, make, model, year, vehicle_value, status, written_premium, bound_at) " +
                        "  VALUES (?::uuid, 'P13-MOTOR-1', 'Toyota', 'Corolla', 2020, 10000, 'active', 700, TIMESTAMPTZ '2026-01-15 08:00:00+00')")) {
                    ps.setString(1, schemeId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO " + qualified + "properties " +
                        "  (scheme_id, property_name, address, sum_insured, status, written_premium, bound_at) " +
                        "  VALUES (?::uuid, 'P13 House', '12 IT Road', 200000, 'active', 1500, TIMESTAMPTZ '2026-01-15 08:00:00+00')")) {
                    ps.setString(1, schemeId);
                    ps.executeUpdate();
                }

                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO " + qualified + "providers (name) VALUES ('P13 Provider')")) {
                    ps.executeUpdate();
                }
                conn.commit();
            } catch (Exception seedFailure) {
                conn.rollback();
                throw seedFailure;
            } finally {
                conn.setAutoCommit(true);
            }
        }

        // Stage 2 — run migrations to head. V111/V112 backfills fire; V113 defaults network_tier.
        Flyway toLatest = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration/tenant")
                .schemas(schema)
                .load();
        toLatest.migrate();

        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {

            // Exactly one initial_backfill row per seeded policy, per source.
            for (String source : List.of("LIFE_POLICY", "FUNERAL_POLICY", "DISABILITY_POLICY",
                    "TRAVEL_POLICY", "VEHICLE_POLICY", "PROPERTY_POLICY")) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT COUNT(*) FROM " + qualified + "policy_status_history " +
                        " WHERE policy_source = ? AND reason_code = 'initial_backfill' " +
                        "   AND actor_email = 'migration' AND to_status = 'active'")) {
                    ps.setString(1, source);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        assertThat(rs.getLong(1))
                                .as("%s must have exactly one initial_backfill row", source)
                                .isEqualTo(1);
                    }
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM " + qualified + "policy_status_history")) {
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getLong(1)).as("no stray policy history rows").isEqualTo(6);
                }
            }
            // effective_at preserves bound_at (invariant #10 — no snapping).
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM " + qualified + "policy_status_history " +
                    " WHERE effective_at = TIMESTAMPTZ '2026-01-15 08:00:00+00'")) {
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getLong(1))
                            .as("every policy backfill row carries bound_at as effective_at")
                            .isEqualTo(6);
                }
            }

            // Member backfill: active → 1 row; terminated → 2; suspended → 2.
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT m.member_number, COUNT(h.id) AS rows " +
                    "  FROM " + qualified + "members m " +
                    "  LEFT JOIN " + qualified + "member_status_history h ON h.member_id = m.id " +
                    " GROUP BY m.member_number")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        int expected = switch (rs.getString("member_number")) {
                            case "P13-ACTIVE" -> 1;
                            case "P13-TERM", "P13-SUSP" -> 2;
                            default -> -1;
                        };
                        assertThat(rs.getLong("rows"))
                                .as("%s member_status_history row count", rs.getString("member_number"))
                                .isEqualTo(expected);
                    }
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM " + qualified + "member_status_history " +
                    " WHERE reason_code = 'backfill_from_termination_date' " +
                    "   AND from_status = 'active' AND to_status = 'terminated' " +
                    "   AND effective_at = TIMESTAMPTZ '2026-03-31 00:00:00+00'")) {
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getLong(1))
                            .as("terminated member's second row fires at termination_date midnight UTC")
                            .isEqualTo(1);
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT reason_note FROM " + qualified + "member_status_history " +
                    " WHERE reason_code = 'backfill_from_suspend_reason' " +
                    "   AND effective_at = TIMESTAMPTZ '2026-04-15 10:30:00+00'")) {
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getString("reason_note"))
                            .as("suspended member's second row carries suspend_reason")
                            .isEqualTo("arrears_non_payment");
                }
            }
            // FK integrity — no orphan member_status_history rows.
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM " + qualified + "member_status_history h " +
                    "  LEFT JOIN " + qualified + "members m ON m.id = h.member_id " +
                    " WHERE m.id IS NULL")) {
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getLong(1)).as("no orphan member_status_history rows").isZero();
                }
            }

            // V113 default — every existing provider lands on STANDARD.
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT DISTINCT network_tier FROM " + qualified + "providers")) {
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("network_tier")).isEqualTo("STANDARD");
                    assertThat(rs.next()).as("only STANDARD present post-backfill").isFalse();
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

    /**
     * V141 append-only guard: once a row lands in a terminal status
     * (completed/failed), subsequent UPDATEs must raise. Seeds a job row,
     * transitions to completed, then attempts a second UPDATE and asserts
     * the trigger fires. Protects the audit-trail invariant per A16 +
     * Grill note 21 — a consumer bug that tried to rewrite the row would
     * be silent otherwise.
     */
    @Test
    void v141_actuarialReportJob_appendOnlyAfterTerminalStatus() throws Exception {
        String schema = "tenant_v141_append_only_it";

        Flyway flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration/tenant")
                .schemas(schema)
                .createSchemas(true)
                .load();
        flyway.migrate();

        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            String jobId;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO " + schema + ".actuarial_report_job " +
                    "  (tenant_id, report_key, status, params_json, params_hash, requested_by_email) " +
                    "  VALUES (gen_random_uuid(), 'IBNR_TRIANGLE', 'requested', '{}'::jsonb, 'h1', 'it@test') " +
                    "  RETURNING job_id")) {
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    jobId = rs.getString(1);
                }
            }

            // First terminal write from 'requested' → 'completed' — permitted.
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE " + schema + ".actuarial_report_job " +
                    "   SET status = 'completed', completed_at = NOW(), result_json = '{\"k\":1}'::jsonb " +
                    " WHERE job_id = ?::uuid")) {
                ps.setString(1, jobId);
                assertThat(ps.executeUpdate()).isEqualTo(1);
            }

            // Second UPDATE must raise via the trigger.
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE " + schema + ".actuarial_report_job " +
                    "   SET result_json = '{\"k\":2}'::jsonb WHERE job_id = ?::uuid")) {
                ps.setString(1, jobId);
                assertThatThrownBy(ps::executeUpdate)
                        .isInstanceOf(java.sql.SQLException.class)
                        .hasMessageContaining("append-only after terminal status");
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

    private static void assertConstraintExists(Connection conn, String schema, String table,
                                               String constraintName) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM information_schema.table_constraints " +
                " WHERE constraint_schema = ? AND table_name = ? AND constraint_name = ?")) {
            ps.setString(1, schema);
            ps.setString(2, table);
            ps.setString(3, constraintName);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next())
                        .as("%s.%s must carry constraint %s", schema, table, constraintName)
                        .isTrue();
            }
        }
    }

    private static void assertIndexExists(Connection conn, String schema,
                                          String indexName) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM pg_indexes WHERE schemaname = ? AND indexname = ?")) {
            ps.setString(1, schema);
            ps.setString(2, indexName);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next())
                        .as("index %s must exist in %s", indexName, schema)
                        .isTrue();
            }
        }
    }
}

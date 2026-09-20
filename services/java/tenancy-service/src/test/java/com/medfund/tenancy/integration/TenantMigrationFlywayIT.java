package com.medfund.tenancy.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
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
import java.util.UUID;

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

    /**
     * Phase 17 §0.2: tenant V168 adds a cross-schema FK to
     * {@code public.tenant_report_schedule}, so the public migration set
     * must land in the shared container before any tenant migration runs.
     * Mirrors production tenancy-service application.yml where both
     * locations are configured on the same Flyway instance.
     */
    @BeforeAll
    static void applyPublicMigrationsOnce() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration/public")
                .schemas("public")
                .load()
                .migrate();
    }

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

            // Phase 13 §A — V111 / V112 history tables. V113's
            // providers.network_tier went with the table in V276; the
            // per-tenant tier now lives on public.provider_tenants.
            assertColumns(conn, "tenant_it", "policy_status_history", List.of(
                    "policy_id", "policy_source", "from_status", "to_status",
                    "effective_at", "actor_id", "actor_email", "reason_code", "reason_note"));
            assertColumns(conn, "tenant_it", "member_status_history", List.of(
                    "member_id", "from_status", "to_status",
                    "effective_at", "actor_id", "actor_email", "reason_code", "reason_note"));
            assertTableAbsent(conn, "tenant_it", "providers");

            // Phase 14 §A/B/D — V139 claim_reserve_history + V140 member death columns
            // + V141 report_job (renamed to report_job by V151 in Phase 15 §1,
            // widened with retention_class + parent_job_id).
            assertColumns(conn, "tenant_it", "claim_reserve_history", List.of(
                    "id", "claim_id", "reserved_amount", "effective_at",
                    "actor_id", "actor_email", "reason_note", "created_at"));
            assertIndexExists(conn, "tenant_it", "idx_crh_claim_time");

            assertColumns(conn, "tenant_it", "members", List.of("death_date", "cause_of_death"));

            assertColumns(conn, "tenant_it", "report_job", List.of(
                    "job_id", "tenant_id", "report_key", "status",
                    "params_json", "params_hash", "result_json", "error_message",
                    "requested_at", "completed_at", "requested_by", "requested_by_email",
                    "retention_class", "parent_job_id"));
            assertIndexExists(conn, "tenant_it", "idx_arj_lookup");
            assertIndexExists(conn, "tenant_it", "ux_arj_inflight");
            assertIndexExists(conn, "tenant_it", "ix_report_job_parent");
            assertIndexExists(conn, "tenant_it", "ix_report_job_retention");

            // Phase 15 §6 — V158 widens ifrs17_cohort with the locked-in yield curve snapshot.
            assertColumns(conn, "tenant_it", "ifrs17_cohort",
                    List.of("locked_in_yield_curve_snapshot", "locked_in_at"));
            assertIndexExists(conn, "tenant_it", "ix_ifrs17_cohort_locked_in");

            // Phase 15 §7 — V160 tenant-admin overrides on auto-derived opening balances.
            assertColumns(conn, "tenant_it", "ifrs17_opening_balance_seed", List.of(
                    "portfolio_id", "cohort_id", "currency", "balance_type", "amount",
                    "effective_from", "reason_note", "actor_id", "actor_email", "created_at"));
            assertIndexExists(conn, "tenant_it", "ix_ifrs17_opening_balance_seed_lookup");

            // Phase 15 §8 — V161/V162/V163/V164 VFA underlying-item entity model.
            assertColumns(conn, "tenant_it", "unit_linked_fund", List.of(
                    "name", "currency", "base_asset_class", "is_active",
                    "created_at", "updated_at", "actor_id", "actor_email"));
            assertIndexExists(conn, "tenant_it", "ix_unit_linked_fund_active");

            assertColumns(conn, "tenant_it", "fund_nav_history", List.of(
                    "fund_id", "valuation_date", "nav_per_unit", "source",
                    "created_at", "actor_id", "actor_email"));
            assertIndexExists(conn, "tenant_it", "ix_fund_nav_history_lookup");

            assertColumns(conn, "tenant_it", "policy_unit_ledger", List.of(
                    "policy_id", "fund_id", "transaction_date", "transaction_type",
                    "units", "price", "created_at", "actor_id", "actor_email"));
            assertIndexExists(conn, "tenant_it", "ix_policy_unit_ledger_policy");
            assertIndexExists(conn, "tenant_it", "ix_policy_unit_ledger_fund");

            assertColumns(conn, "tenant_it", "variable_fee_schedule", List.of(
                    "fund_id", "effective_from", "effective_to", "fee_percentage",
                    "created_at", "actor_id", "actor_email"));
            assertIndexExists(conn, "tenant_it", "ix_variable_fee_schedule_lookup");

            // Phase 16 §B REG7 — V166 PMB classification columns on claims + partial index.
            assertColumns(conn, "tenant_it", "claims", List.of("is_pmb", "pmb_condition_code"));
            assertIndexExists(conn, "tenant_it", "ix_claims_pmb");

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
     * Public V185 + tenant V275 — platform provider membership.
     *
     * <p>V185 creates the two junction tables and extends
     * {@code provision_tenant_role} so a tenant role can read them; V275
     * retargets the three hard provider FKs from the tenant-local
     * {@code providers} table to {@code public.providers}. Without the grant
     * every tenant-scoped provider read in claims-service and finance-service
     * fails with {@code permission denied}; without the retarget the FKs go
     * dangling the moment V276 drops the shadow table.
     */
    @Test
    void v185AndV275_landMembershipTables_grantTenantRole_andRetargetProviderFks() throws Exception {
        // Idempotent — a no-op if tenantMigrations_landAllExpectedColumns
        // already migrated tenant_it on this shared container.
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration/tenant")
                .schemas("tenant_it")
                .createSchemas(true)
                .load()
                .migrate();

        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {

            // V185 — both junction tables land with their full column set.
            assertColumns(conn, "public", "provider_tenants", List.of(
                    "provider_id", "tenant_id", "status", "network_tier", "in_network",
                    "contract_effective_from", "contract_effective_to",
                    "credit_limit", "credit_limit_currency", "tariff_agreement_id",
                    "created_at", "updated_at", "created_by", "updated_by"));
            assertColumns(conn, "public", "provider_insurance_lines", List.of(
                    "provider_id", "insurance_line", "created_at"));

            assertConstraintExists(conn, "public", "provider_tenants", "provider_tenants_status_ck");
            assertConstraintExists(conn, "public", "provider_tenants", "provider_tenants_tier_ck");
            assertConstraintExists(conn, "public", "provider_tenants", "provider_tenants_dates_ck");
            assertConstraintExists(conn, "public", "provider_insurance_lines",
                    "provider_insurance_lines_ck");
            assertIndexExists(conn, "public", "ix_provider_tenants_tenant");
            assertIndexExists(conn, "public", "ix_provider_tenants_status");
            assertIndexExists(conn, "public", "ix_provider_insurance_lines_line");

            // V185 — provision_tenant_role grants SELECT on both new tables.
            // The IT ships with no public.tenants rows, so V185's backfill loop
            // had nothing to re-provision; call the function directly instead.
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT public.provision_tenant_role('tenant_it')")) {
                ps.execute();
            }
            for (String table : List.of("provider_tenants", "provider_insurance_lines")) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT has_table_privilege('tenant_it_role', ?, 'SELECT')")) {
                    ps.setString(1, "public." + table);
                    try (ResultSet rs = ps.executeQuery()) {
                        assertThat(rs.next()).isTrue();
                        assertThat(rs.getBoolean(1))
                                .as("tenant_it_role must be able to read public.%s", table)
                                .isTrue();
                    }
                }
            }

            // V275 — the three hard FKs now point at public.providers, and no
            // FK on those tables still points at the tenant-local shadow.
            for (String table : List.of("claims", "payments", "quotations")) {
                assertConstraintExists(conn, "tenant_it", table,
                        table + "_provider_id_public_fkey");
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT con.conname " +
                    "  FROM pg_constraint con " +
                    "  JOIN pg_class rel      ON rel.oid  = con.conrelid " +
                    "  JOIN pg_namespace ns   ON ns.oid   = rel.relnamespace " +
                    "  JOIN pg_class fref     ON fref.oid = con.confrelid " +
                    "  JOIN pg_namespace fns  ON fns.oid  = fref.relnamespace " +
                    " WHERE con.contype = 'f' AND ns.nspname = 'tenant_it' " +
                    "   AND fns.nspname = 'tenant_it' AND fref.relname = 'providers'")) {
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next())
                            .as("no tenant FK may still reference tenant_it.providers after V275")
                            .isFalse();
                }
            }
        }
    }

    /**
     * V186 — deleting a tenant cascades its provider memberships away.
     *
     * <p>V185 created both of {@code provider_tenants}' foreign keys as
     * ON DELETE RESTRICT. On the tenant side that made a tenant undeletable
     * the moment one provider was linked to it, which blocks offboarding and
     * aborts {@code scripts/reset-tenant-schemas.sh}. Every other table
     * referencing {@code public.tenants} cascades; this pins that
     * {@code provider_tenants} now does too, while the provider side stays
     * RESTRICT so a contracted provider cannot be deleted out from under a
     * tenant.
     */
    @Test
    void v186_tenantDeleteCascadesMemberships_whileProviderDeleteStaysRestricted() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {

            assertThat(fkDeleteAction(conn, "provider_tenants", "provider_tenants_tenant_id_fkey"))
                    .as("provider_tenants.tenant_id must cascade when a tenant is deleted")
                    .isEqualTo("c");
            assertThat(fkDeleteAction(conn, "provider_tenants", "provider_tenants_provider_id_fkey"))
                    .as("provider_tenants.provider_id must stay RESTRICT")
                    .isEqualTo("r");

            // End to end: a tenant carrying a membership deletes cleanly and
            // takes only the membership with it, leaving the registry row.
            UUID tenantId = UUID.randomUUID();
            UUID providerId = UUID.randomUUID();
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO public.tenants (id, name, slug, schema_name, status) "
                    + "VALUES (?, 'V186 Cascade Co', 'v186-cascade', 'tenant_v186_cascade', 'active')")) {
                ps.setObject(1, tenantId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO public.providers (id, name) VALUES (?, 'V186 Cascade Clinic')")) {
                ps.setObject(1, providerId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO public.provider_tenants (provider_id, tenant_id) VALUES (?, ?)")) {
                ps.setObject(1, providerId);
                ps.setObject(2, tenantId);
                ps.executeUpdate();
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM public.tenants WHERE id = ?")) {
                ps.setObject(1, tenantId);
                assertThat(ps.executeUpdate())
                        .as("deleting a tenant with a linked provider must succeed")
                        .isEqualTo(1);
            }

            assertThat(countWhereProvider(conn, "public.provider_tenants", providerId))
                    .as("the membership row goes with the tenant")
                    .isZero();
            assertThat(countWhereProvider(conn, "public.providers", providerId))
                    .as("the platform provider row survives — it is shared, not tenant-owned")
                    .isEqualTo(1);

            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM public.providers WHERE id = ?")) {
                ps.setObject(1, providerId);
                ps.executeUpdate();
            }
        }
    }

    /** {@code pg_constraint.confdeltype} for one FK: 'c' = CASCADE, 'r' = RESTRICT. */
    private static String fkDeleteAction(Connection conn, String table, String constraint)
            throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT confdeltype FROM pg_constraint "
                + " WHERE conname = ? AND conrelid = ?::regclass AND contype = 'f'")) {
            ps.setString(1, constraint);
            ps.setString(2, "public." + table);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("constraint %s must exist", constraint).isTrue();
                return rs.getString(1);
            }
        }
    }

    private static int countWhereProvider(Connection conn, String table, UUID providerId)
            throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT count(*) FROM " + table + " WHERE " + ("public.providers".equals(table) ? "id" : "provider_id") + " = ?")) {
            ps.setObject(1, providerId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /**
     * Tenant V276 — the shadow {@code providers} table is gone, and the
     * {@code DROP TABLE ... CASCADE} did not take the V275-retargeted FKs
     * with it.
     *
     * <p>CASCADE is the sharp edge here: it drops every dependent object.
     * The three retargeted constraints reference {@code public.providers},
     * not the shadow, so they must survive. If a future edit ever lands a
     * V275 that misses one, CASCADE would silently delete that FK here
     * instead of failing the migration, and the column would go unconstrained.
     */
    @Test
    void v276_dropsShadowProviders_andLeavesRetargetedFksIntact() throws Exception {
        String schema = "tenant_v276_drop_it";

        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration/tenant")
                .schemas(schema)
                .createSchemas(true)
                .load()
                .migrate();

        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {

            assertTableAbsent(conn, schema, "providers");

            for (String table : List.of("claims", "payments", "quotations")) {
                assertConstraintExists(conn, schema, table, table + "_provider_id_public_fkey");
            }
        }
    }

    /**
     * Tenant V276 must be a no-op in the {@code public} schema.
     *
     * <p>Local dev runs Flyway over {@code db/migration/public} AND
     * {@code db/migration/tenant} with {@code schemas: public}
     * (tenancy-service application.yml), so every tenant migration also
     * executes once with {@code current_schema() = 'public'}. Unqualified
     * {@code DROP TABLE providers} there resolves to {@code public.providers},
     * the platform registry that {@code provider_tenants} and
     * {@code provider_insurance_lines} both reference. The orphan guard is no
     * protection: it would compare the registry to itself and always find zero
     * orphans.
     *
     * <p>This test reproduces the dev layout exactly (both locations, one
     * shared history, schema {@code public}) and asserts the registry and its
     * rows survive.
     */
    @Test
    void v276_isNoOpInPublicSchema_soThePlatformRegistrySurvives() throws Exception {
        // Needs its own database: the shared container's public schema already
        // had db/migration/public applied by @BeforeAll, so replaying the dev
        // layout on top of it fails at tenant V001 ("providers already exists")
        // long before reaching V276. A fresh database lets both locations run
        // interleaved in version order, exactly as local dev does.
        String devShapeDb = "medfund_dev_shape_it";
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement ps = conn.prepareStatement("CREATE DATABASE " + devShapeDb)) {
            ps.execute();
        }
        String devShapeUrl = POSTGRES.getJdbcUrl()
                .replaceFirst("/" + POSTGRES.getDatabaseName() + "(\\?|$)", "/" + devShapeDb + "$1");

        // The dev shape: both locations, one shared history, schema = public.
        Flyway.configure()
                .dataSource(devShapeUrl, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration/public", "classpath:db/migration/tenant")
                .schemas("public")
                .baselineOnMigrate(true)
                .outOfOrder(true)
                .load()
                .migrate();

        try (Connection conn = DriverManager.getConnection(
                devShapeUrl, POSTGRES.getUsername(), POSTGRES.getPassword())) {

            // The registry survived V276 as a table...
            assertColumns(conn, "public", "providers", List.of("id", "name", "status"));

            // ...and is still writable through the junction FKs, which is the
            // property CASCADE would have destroyed.
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO public.providers (name, status) " +
                    "VALUES ('V276 Registry Survivor', 'active') RETURNING id");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                String providerId = rs.getString(1);
                try (PreparedStatement link = conn.prepareStatement(
                        "INSERT INTO public.provider_insurance_lines (provider_id, insurance_line) " +
                        "VALUES (?::uuid, 'HEALTH')")) {
                    link.setString(1, providerId);
                    assertThat(link.executeUpdate())
                            .as("provider_insurance_lines must still FK to a live public.providers")
                            .isOne();
                }
            }

            assertConstraintExists(conn, "public", "provider_tenants", "provider_tenants_status_ck");
        }
    }

    /**
     * Tenant V276 orphan guard: a shadow provider row that carries a
     * {@code keycloak_user_id} but has no counterpart in
     * {@code public.providers} is real operator-created data that the drop
     * would destroy silently. The migration must refuse rather than proceed.
     *
     * <p>Stage at V275 (the last migration before the drop), seed exactly
     * that row, then let Flyway run V276 and assert it raises.
     */
    @Test
    void v276_orphanGuard_refusesToDropWhenKeycloakBackedProviderIsUnmirrored() throws Exception {
        String schema = "tenant_v276_orphan_it";

        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration/tenant")
                .schemas(schema)
                .createSchemas(true)
                .target("275")
                .load()
                .migrate();

        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO " + schema + ".providers (name, keycloak_user_id) " +
                     "VALUES ('Unmirrored Clinic', 'kc-user-v276')")) {
            ps.executeUpdate();
        }

        Flyway toHead = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration/tenant")
                .schemas(schema)
                .load();

        assertThatThrownBy(toHead::migrate)
                .as("V276 must refuse to drop a shadow carrying unmirrored Keycloak-backed rows")
                .hasMessageContaining("Unmirrored Clinic");

        // And the guard is a refusal, not a partial drop: the row is still there.
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM " + schema + ".providers");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            assertThat(rs.getLong(1)).as("guarded shadow table must survive intact").isOne();
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

            // V113's providers.network_tier column, its CHECK and its index
            // all went with the shadow table in V276. Asserting the table is
            // absent is the standing guard that nothing reintroduces it.
            assertTableAbsent(conn, schema, "providers");

            // Named CHECK constraints from V111 / V112.
            assertConstraintExists(conn, schema, "policy_status_history", "chk_policy_status_history_source");
            assertConstraintExists(conn, schema, "member_status_history", "chk_member_status_history_reason");

            // Indexes the report queries lean on.
            assertIndexExists(conn, schema, "ix_policy_status_history_policy_effective");
            assertIndexExists(conn, schema, "ix_policy_status_history_source_status_effective");
            assertIndexExists(conn, schema, "ix_member_status_history_member_effective");
            assertIndexExists(conn, schema, "ix_member_status_history_status_effective");
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

                // No provider fixture: V113's network_tier backfill ran against
                // the tenant-local providers table, which V276 drops before
                // this staged migration reaches head. Per-tenant network tier
                // now lives on public.provider_tenants.
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

            // V113's providers.network_tier default is no longer observable
            // here: V276 drops the shadow table on the way to head. The
            // platform equivalent (public.provider_tenants.network_tier,
            // defaulted to STANDARD) is covered by
            // v185AndV275_landMembershipTables_grantTenantRole_andRetargetProviderFks.
            assertTableAbsent(conn, schema, "providers");
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
                    "INSERT INTO " + schema + ".report_job " +
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
                    "UPDATE " + schema + ".report_job " +
                    "   SET status = 'completed', completed_at = NOW(), result_json = '{\"k\":1}'::jsonb " +
                    " WHERE job_id = ?::uuid")) {
                ps.setString(1, jobId);
                assertThat(ps.executeUpdate()).isEqualTo(1);
            }

            // Second UPDATE must raise via the trigger.
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE " + schema + ".report_job " +
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

    private static void assertTableAbsent(Connection conn, String schema, String table)
            throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM information_schema.tables " +
                " WHERE table_schema = ? AND table_name = ?")) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next())
                        .as("%s.%s must not exist", schema, table)
                        .isFalse();
            }
        }
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

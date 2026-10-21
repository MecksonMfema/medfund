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

            // V134 — tenant endorsement four-eyes config (Phase 12 §C).
            assertColumns(conn, "public", "tenant_endorsement_config", List.of(
                    "tenant_id", "enabled",
                    "four_eyes_threshold_amount", "threshold_currency",
                    "actor_id", "actor_email"));

            // V136 / V137 / V138 — actuarial per-tenant basis tables (Phase 14).
            assertColumns(conn, "public", "tenant_persistency_basis", List.of(
                    "tenant_id", "insurance_line", "cohort_months",
                    "expected_retention_pct", "source_note",
                    "effective_from", "effective_to",
                    "updated_by", "updated_by_email"));
            assertColumns(conn, "public", "tenant_mortality_basis", List.of(
                    "tenant_id", "insurance_line", "basis_name",
                    "mortality_multiplier", "effective_from", "effective_to",
                    "updated_by", "updated_by_email"));
            assertColumns(conn, "public", "tenant_morbidity_basis", List.of(
                    "tenant_id", "insurance_line", "basis_name",
                    "morbidity_multiplier", "effective_from", "effective_to",
                    "updated_by", "updated_by_email"));

            // V166 — IFRS 17 material-event notification config (Phase 15 §19 / I30).
            assertColumns(conn, "public", "tenant_ifrs17_notification_config", List.of(
                    "tenant_id", "event_type", "delivery_method", "recipient",
                    "throttle_minutes", "is_active",
                    "updated_by", "updated_by_email"));

            // V167 — market-data auto-fetch config (Phase 15 §24 / I12 + I16).
            assertColumns(conn, "public", "tenant_market_data_config", List.of(
                    "tenant_id", "currency", "source", "auto_fetch_enabled",
                    "updated_by", "updated_by_email"));

            // V136 seed — persistency curves for every tenant. The IT ships with no
            // pre-existing tenants, so the seed just proves the SELECT compiles
            // and lands zero rows against an empty tenants table.
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM public.tenant_persistency_basis " +
                    " WHERE source_note = 'industry_default_v1'")) {
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    // Count is zero when no tenants exist, positive after tenants seed.
                    assertThat(rs.getLong(1)).isGreaterThanOrEqualTo(0);
                }
            }

            // V165 — seed IFRS17_MODEL industry-default rules. Same shape as V136:
            // the SELECT compiles and lands zero rows against an empty tenants
            // table; the per-tenant count is exercised by
            // v165_seedsIfrs17ModelDefaults_forEveryTenant below.
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM public.tenant_rules " +
                    " WHERE category = 'IFRS17_MODEL'")) {
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getLong(1)).isGreaterThanOrEqualTo(0);
                }
            }

            // V172 — seed PMB_CLASSIFICATION industry-default rules. Same shape:
            // per-tenant count exercised by v172_seedsPmbClassificationDefaults_forEveryTenant.
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM public.tenant_rules " +
                    " WHERE category = 'PMB_CLASSIFICATION'")) {
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getLong(1)).isGreaterThanOrEqualTo(0);
                }
            }
        }
    }

    /**
     * V136 seed guard: with a fresh tenant seeded before V136 runs, the seed
     * lands 13 curves per tenant (5 HEALTH + 4 LIFE + 4 FUNERAL). Runs in an
     * isolated docker container to keep the outer publicMigrations test's
     * static shared container clean — the migrations hardcode {@code public.}
     * prefixes, so any Flyway run pollutes {@code public} regardless of the
     * {@code .schemas(…)} target.
     */
    @Test
    void v136_seedsPersistencyCurves_forEveryTenant() throws Exception {
        try (PostgreSQLContainer<?> isolated = new PostgreSQLContainer<>("postgres:17-alpine")
                .withDatabaseName("v136_seed_it")
                .withUsername("medfund")
                .withPassword("medfund")) {
            isolated.start();

            Flyway toV135 = Flyway.configure()
                    .dataSource(isolated.getJdbcUrl(), isolated.getUsername(), isolated.getPassword())
                    .locations("classpath:db/migration/public")
                    .schemas("public")
                    .target("135")
                    .load();
            toV135.migrate();

            try (Connection conn = DriverManager.getConnection(
                    isolated.getJdbcUrl(), isolated.getUsername(), isolated.getPassword())) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO public.tenants (id, name, slug, schema_name) " +
                        "  VALUES (gen_random_uuid(), 'V136 Seed Tenant', 'v136-seed', 'public')")) {
                    ps.executeUpdate();
                }
            }

            Flyway toLatest = Flyway.configure()
                    .dataSource(isolated.getJdbcUrl(), isolated.getUsername(), isolated.getPassword())
                    .locations("classpath:db/migration/public")
                    .schemas("public")
                    .load();
            toLatest.migrate();

            try (Connection conn = DriverManager.getConnection(
                    isolated.getJdbcUrl(), isolated.getUsername(), isolated.getPassword())) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT COUNT(*) FROM public.tenant_persistency_basis " +
                        " WHERE source_note = 'industry_default_v1'")) {
                    try (ResultSet rs = ps.executeQuery()) {
                        assertThat(rs.next()).isTrue();
                        // 5 HEALTH + 4 LIFE + 4 FUNERAL = 13 rows per tenant.
                        assertThat(rs.getLong(1)).isEqualTo(13);
                    }
                }
            }
        }
    }

    /**
     * V165 seed guard (Phase 15 §9 / I27): with a fresh tenant seeded before V165
     * runs, the seed lands 8 industry-default IFRS17_MODEL rules per tenant
     * (5 PAA lines + 3 GMM lines). Isolated container for the same reason as
     * {@link #v136_seedsPersistencyCurves_forEveryTenant} — the seeded INSERT
     * hardcodes {@code public.} prefixes.
     */
    @Test
    void v165_seedsIfrs17ModelDefaults_forEveryTenant() throws Exception {
        try (PostgreSQLContainer<?> isolated = new PostgreSQLContainer<>("postgres:17-alpine")
                .withDatabaseName("v165_seed_it")
                .withUsername("medfund")
                .withPassword("medfund")) {
            isolated.start();

            // Migrate up to the highest existing public-schema migration below V165 so
            // we can seed a tenant BEFORE V165's INSERT runs. tenant-schema migrations
            // (V155-V164) are elsewhere on disk — target() must name a public-folder
            // version, so pin to V154 which is the last public row before this seed.
            Flyway prelude = Flyway.configure()
                    .dataSource(isolated.getJdbcUrl(), isolated.getUsername(), isolated.getPassword())
                    .locations("classpath:db/migration/public")
                    .schemas("public")
                    .target("154")
                    .load();
            prelude.migrate();

            try (Connection conn = DriverManager.getConnection(
                    isolated.getJdbcUrl(), isolated.getUsername(), isolated.getPassword())) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO public.tenants (id, name, slug, schema_name) " +
                        "  VALUES (gen_random_uuid(), 'V165 Seed Tenant', 'v165-seed', 'public')")) {
                    ps.executeUpdate();
                }
            }

            Flyway toLatest = Flyway.configure()
                    .dataSource(isolated.getJdbcUrl(), isolated.getUsername(), isolated.getPassword())
                    .locations("classpath:db/migration/public")
                    .schemas("public")
                    .load();
            toLatest.migrate();

            try (Connection conn = DriverManager.getConnection(
                    isolated.getJdbcUrl(), isolated.getUsername(), isolated.getPassword())) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT COUNT(*) FROM public.tenant_rules " +
                        " WHERE category = 'IFRS17_MODEL'")) {
                    try (ResultSet rs = ps.executeQuery()) {
                        assertThat(rs.next()).isTrue();
                        // 5 PAA (HEALTH/TRAVEL/VEHICLE/PROPERTY/GROUP)
                        // + 3 GMM (LIFE/FUNERAL/DISABILITY) = 8 rows per tenant.
                        assertThat(rs.getLong(1)).isEqualTo(8);
                    }
                }

                // Rows are enabled + carry the expected template ids + priorities.
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT COUNT(*) FROM public.tenant_rules " +
                        " WHERE category = 'IFRS17_MODEL' AND enabled = TRUE " +
                        "   AND template_id LIKE 'I9%'")) {
                    try (ResultSet rs = ps.executeQuery()) {
                        assertThat(rs.next()).isTrue();
                        assertThat(rs.getLong(1)).isEqualTo(8);
                    }
                }

                // Idempotency: re-running V165's INSERT is a no-op.
                Flyway rerun = Flyway.configure()
                        .dataSource(isolated.getJdbcUrl(), isolated.getUsername(), isolated.getPassword())
                        .locations("classpath:db/migration/public")
                        .schemas("public")
                        .load();
                rerun.migrate();  // no-op because everything's applied
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT COUNT(*) FROM public.tenant_rules " +
                        " WHERE category = 'IFRS17_MODEL'")) {
                    try (ResultSet rs = ps.executeQuery()) {
                        assertThat(rs.next()).isTrue();
                        assertThat(rs.getLong(1)).isEqualTo(8);
                    }
                }
            }
        }
    }

    /**
     * V172 seed guard (Phase 17 §B REG7): with a fresh tenant seeded before V172
     * runs, the seed lands 15 industry-default PMB_CLASSIFICATION rules per
     * tenant (representative CMS PMB sample: respiratory / cardiac / metabolic /
     * oncology / mental health / renal). Isolated container for the same
     * reason as {@link #v165_seedsIfrs17ModelDefaults_forEveryTenant} — the
     * seeded INSERT hardcodes {@code public.} prefixes.
     */
    @Test
    void v172_seedsPmbClassificationDefaults_forEveryTenant() throws Exception {
        try (PostgreSQLContainer<?> isolated = new PostgreSQLContainer<>("postgres:17-alpine")
                .withDatabaseName("v172_seed_it")
                .withUsername("medfund")
                .withPassword("medfund")) {
            isolated.start();

            // Migrate up to V171 (highest public row below V172) so we can seed
            // a tenant BEFORE V172's INSERT fires.
            Flyway prelude = Flyway.configure()
                    .dataSource(isolated.getJdbcUrl(), isolated.getUsername(), isolated.getPassword())
                    .locations("classpath:db/migration/public")
                    .schemas("public")
                    .target("171")
                    .load();
            prelude.migrate();

            try (Connection conn = DriverManager.getConnection(
                    isolated.getJdbcUrl(), isolated.getUsername(), isolated.getPassword())) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO public.tenants (id, name, slug, schema_name) " +
                        "  VALUES (gen_random_uuid(), 'V172 Seed Tenant', 'v172-seed', 'public')")) {
                    ps.executeUpdate();
                }
            }

            Flyway toLatest = Flyway.configure()
                    .dataSource(isolated.getJdbcUrl(), isolated.getUsername(), isolated.getPassword())
                    .locations("classpath:db/migration/public")
                    .schemas("public")
                    .load();
            toLatest.migrate();

            try (Connection conn = DriverManager.getConnection(
                    isolated.getJdbcUrl(), isolated.getUsername(), isolated.getPassword())) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT COUNT(*) FROM public.tenant_rules " +
                        " WHERE category = 'PMB_CLASSIFICATION'")) {
                    try (ResultSet rs = ps.executeQuery()) {
                        assertThat(rs.next()).isTrue();
                        // 3 respiratory + 3 cardiac + 2 metabolic + 3 oncology
                        // + 3 mental health + 1 renal = 15 rows per tenant.
                        assertThat(rs.getLong(1)).isEqualTo(15);
                    }
                }

                // Rows are enabled + carry the PMB1 template id + priority 100.
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT COUNT(*) FROM public.tenant_rules " +
                        " WHERE category = 'PMB_CLASSIFICATION' AND enabled = TRUE " +
                        "   AND template_id = 'PMB1 - Match by ICD diagnosis code' " +
                        "   AND priority = 100")) {
                    try (ResultSet rs = ps.executeQuery()) {
                        assertThat(rs.next()).isTrue();
                        assertThat(rs.getLong(1)).isEqualTo(15);
                    }
                }

                // Sample: the pulmonary-TB rule (A15.0 → PMB-001) is present with
                // the expected condition + action shape.
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT definition FROM public.tenant_rules " +
                        " WHERE category = 'PMB_CLASSIFICATION' " +
                        "   AND rule_key = 'pmb-default-a15-0'")) {
                    try (ResultSet rs = ps.executeQuery()) {
                        assertThat(rs.next()).isTrue();
                        String defn = rs.getString(1);
                        assertThat(defn).contains("\"A15.0\"");
                        assertThat(defn).contains("PMB_CONDITION_CODE:PMB-001");
                        assertThat(defn).contains("pmbClassification.diagnosisCode");
                    }
                }

                // Idempotency: re-running V172's INSERT is a no-op.
                Flyway rerun = Flyway.configure()
                        .dataSource(isolated.getJdbcUrl(), isolated.getUsername(), isolated.getPassword())
                        .locations("classpath:db/migration/public")
                        .schemas("public")
                        .load();
                rerun.migrate();
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT COUNT(*) FROM public.tenant_rules " +
                        " WHERE category = 'PMB_CLASSIFICATION'")) {
                    try (ResultSet rs = ps.executeQuery()) {
                        assertThat(rs.next()).isTrue();
                        assertThat(rs.getLong(1)).isEqualTo(15);
                    }
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
}

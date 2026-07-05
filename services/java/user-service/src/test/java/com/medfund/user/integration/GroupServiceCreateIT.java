package com.medfund.user.integration;

import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.testfixtures.AbstractPostgresIntegrationTest;
import com.medfund.shared.testfixtures.TenantTestContext;
import com.medfund.shared.testfixtures.WithTenant;
import com.medfund.user.dto.CreateGroupRequest;
import com.medfund.user.service.GroupService;
import com.medfund.user.service.KeycloakSyncService;
import com.medfund.user.service.UserEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * End-to-end IT for {@link GroupService#create}. Covers the seams unit
 * mocks can't reach — the {@code @Transactional} boundary in
 * particular, where a swallowed SQL error inside
 * {@link com.medfund.user.service.GroupNumberService} used to poison
 * the reactor transaction and turn the whole create into an opaque
 * 500. That specific class of bug is what a
 * {@link GroupService#create} unit test with mocked collaborators
 * fundamentally cannot detect.
 *
 * <p>Cases:
 * <ul>
 *   <li>Happy path — request carries a placeholder
 *       {@code registrationNumber}; server-issued value wins and
 *       lands on the persisted row.</li>
 *   <li>Column-drift — when V125's columns are missing, the create
 *       fails with a clean SQL error instead of a transaction-poisoning
 *       silent 500. Guards against a future regression that re-adds
 *       {@code onErrorResume} on the scheme-load path.</li>
 *   <li>Uniqueness end-to-end — pre-seeding a colliding
 *       {@code registration_number} forces the retry loop to fire
 *       against a real UNIQUE index.</li>
 * </ul>
 *
 * <p>Kafka collaborators ({@code AuditPublisher},
 * {@code UserEventPublisher}) and Keycloak ({@code KeycloakSyncService})
 * are {@code @MockBean}'d — the whole point of the IT is the tx +
 * SQL surface, not those async paths. Postgres is a real container.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
    "spring.flyway.locations=classpath:db/group-create-migration",
    "spring.flyway.baseline-on-migrate=true",
})
@Import(GroupServiceCreateIT.SecurityStub.class)
class GroupServiceCreateIT extends AbstractPostgresIntegrationTest {

    @TestConfiguration
    static class SecurityStub {
        @Bean
        ReactiveJwtDecoder reactiveJwtDecoder() {
            return token -> Mono.just(new Jwt(
                token, Instant.now(), Instant.now().plusSeconds(300),
                Map.of("alg", "none"),
                Map.of("sub", "test", "iss", "test")
            ));
        }
    }

    private static final String TENANT_ID = "00000000-0000-4000-8000-000000000042";
    private static final String ACTOR_ID  = "11111111-1111-4000-8000-000000000001";
    private static final String ACTOR_EMAIL = "actor@test.example";

    @Autowired private GroupService groupService;
    @Autowired private DatabaseClient db;

    // Kafka + Keycloak side-effects are outside the tx-safety surface
    // we're guarding. Mock them at the bean level so a real audit event
    // isn't required for the test to pass.
    @MockBean private AuditPublisher auditPublisher;
    @MockBean private UserEventPublisher eventPublisher;
    @MockBean private KeycloakSyncService keycloakSyncService;

    @BeforeEach
    void resetSchema() {
        // Restore V125 columns idempotently — the drift test drops
        // them, and the container is reused across test methods
        // (Testcontainers withReuse) so we can't rely on a fresh
        // migration between cases.
        db.sql("""
                ALTER TABLE tenants
                    ADD COLUMN IF NOT EXISTS group_number_prefix
                        VARCHAR(20) NOT NULL DEFAULT 'GRP-',
                    ADD COLUMN IF NOT EXISTS group_number_suffix
                        VARCHAR(20) NOT NULL DEFAULT '',
                    ADD COLUMN IF NOT EXISTS group_number_random_length
                        INTEGER NOT NULL DEFAULT 6
                """).then().block();

        // Truncate everything the fixture migration created — order
        // matters for FK cascade. Then re-seed the tenant row with
        // V125 defaults so each test starts clean.
        db.sql("""
                TRUNCATE dependants, members, groups, group_liaisons,
                         staff_users, tenants, scheduled_job_configs, tenant_rules
                CASCADE
                """).then().block();

        db.sql("INSERT INTO tenants (id) VALUES (:id)")
                .bind("id", UUID.fromString(TENANT_ID))
                .then().block();

        // Mockito defaults for the publishers/keycloak — every test
        // needs them so a single BeforeEach hookup is cleaner than
        // per-test @BeforeEach setup.
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        when(eventPublisher.publishGroupLifecycle(any(), any(), any(), any()))
                .thenReturn(Mono.empty());
        when(keycloakSyncService.ensureRealmRole(anyString(), anyString(), anyString()))
                .thenReturn(Mono.empty());
        when(keycloakSyncService.assignRealmRoles(anyString(), anyString(), anyList()))
                .thenReturn(Mono.empty());
    }

    // ------------------------------------------------------------------
    // Happy path — server issues the registration number, request field
    // is ignored, group lands with the generated value.
    // ------------------------------------------------------------------

    @Test
    @WithTenant(TENANT_ID)
    void create_happyPath_generatesServerIssuedNumber_andPersistsGroup() {
        // Request carries a bogus placeholder — GroupService must
        // ignore it and issue its own number per the tenant's shape.
        // Contact email is enough to satisfy the either-or rule.
        var request = new CreateGroupRequest(
                "Acme Corp",
                "IGNORED-BY-SERVER",
                null,
                "billing@acme.example",
                null,   // no liaisonKind
                null);  // no liaisonUserId

        var saved = groupService.create(request, ACTOR_ID, ACTOR_EMAIL)
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(15));

        assertThat(saved).isNotNull();
        assertThat(saved.getName()).isEqualTo("Acme Corp");
        assertThat(saved.getStatus()).isEqualTo("active");
        assertThat(saved.getEmail()).isEqualTo("billing@acme.example");
        // Server-issued value wins — matches the tenant's default
        // shape from V125 (GRP- prefix, 6-digit random block).
        assertThat(saved.getRegistrationNumber())
                .as("server-issued number wins over the request field")
                .matches("^GRP-\\d{6}$");

        // Verify the row actually landed. If the tx got poisoned
        // silently (the bug this IT was born to catch), findById
        // would return empty even though the Mono looked successful.
        var readBack = db.sql("SELECT name, registration_number FROM groups WHERE id = :id")
                .bind("id", saved.getId())
                .map(row -> row.get("registration_number", String.class))
                .one()
                .block(Duration.ofSeconds(10));

        assertThat(readBack).isEqualTo(saved.getRegistrationNumber());
    }

    // ------------------------------------------------------------------
    // Transactional safety — the class of bug the earlier
    // onErrorResume introduced. Drop V125's columns to simulate a
    // pre-migration DB, then verify the create surfaces a CLEAN error
    // rather than a silent 500 downstream.
    // ------------------------------------------------------------------

    @Test
    @WithTenant(TENANT_ID)
    void create_whenSchemeColumnsMissing_failsCleanlyRatherThanSilently() {
        // Recreate the pre-V125 world: drop the three columns
        // GroupNumberService.loadScheme reads. In that state the SELECT
        // errors with 42703 ("column does not exist") and — before the
        // fix — was swallowed by an onErrorResume, poisoning the tx
        // and causing an opaque 500 on the INSERT. Post-fix, the error
        // must propagate up as a real failure that names the missing
        // column so the operator can act.
        db.sql("""
                ALTER TABLE tenants
                    DROP COLUMN IF EXISTS group_number_prefix,
                    DROP COLUMN IF EXISTS group_number_suffix,
                    DROP COLUMN IF EXISTS group_number_random_length
                """).then().block();

        var request = new CreateGroupRequest(
                "PostFix Ltd", null, null, "billing@postfix.example",
                null, null);

        StepVerifier.create(
                groupService.create(request, ACTOR_ID, ACTOR_EMAIL)
                        .contextWrite(TenantTestContext.put()))
                .expectErrorSatisfies(err -> {
                    // Root cause references the missing column; a
                    // regression that re-adds onErrorResume would swap
                    // this for a downstream in_failed_sql_transaction
                    // error instead.
                    String msg = err.getCause() != null
                            ? err.getCause().getMessage()
                            : err.getMessage();
                    assertThat(msg == null ? "" : msg)
                            .as("error should name the missing column, not the downstream tx state")
                            .containsIgnoringCase("group_number");
                })
                .verify(Duration.ofSeconds(10));

        // No row leaked in — transaction rolled back cleanly.
        Long remaining = db.sql("SELECT COUNT(*)::bigint AS n FROM groups")
                .map(row -> row.get("n", Long.class))
                .one()
                .block(Duration.ofSeconds(10));
        assertThat(remaining)
                .as("failed create must not leave a partial row behind")
                .isZero();
    }

    // ------------------------------------------------------------------
    // Uniqueness with a real UNIQUE index. The generator retries on
    // probe collision; this test proves the wire-up against the actual
    // groups_registration_number_unique index — a regression that
    // dropped the index would only show up in prod otherwise.
    // ------------------------------------------------------------------

    @Test
    @WithTenant(TENANT_ID)
    void create_neverProducesCollision_evenWithTightRandomSpace() {
        // Tighten the random space to 3 digits (900 candidates) so a
        // real collision is statistically plausible. Seed 10 existing
        // rows in that space, then create 10 more; assert every new
        // row is unique and has the expected shape. The DB UNIQUE
        // index makes any collision surface as a hard error.
        db.sql("""
                UPDATE tenants SET group_number_random_length = 3
                 WHERE id = :id
                """)
                .bind("id", UUID.fromString(TENANT_ID))
                .then().block();

        for (int i = 100; i < 110; i++) {
            db.sql("INSERT INTO groups (id, name, registration_number) " +
                    "VALUES (:id, :name, :reg)")
                    .bind("id", UUID.randomUUID())
                    .bind("name", "Seed " + i)
                    .bind("reg", "GRP-" + i)
                    .then().block();
        }

        var request = new CreateGroupRequest(
                "New Ltd", null, null, "billing@new.example",
                null, null);

        for (int i = 0; i < 10; i++) {
            var saved = groupService.create(request, ACTOR_ID, ACTOR_EMAIL)
                    .contextWrite(TenantTestContext.put())
                    .block(Duration.ofSeconds(15));
            assertThat(saved).isNotNull();
            assertThat(saved.getRegistrationNumber())
                    .as("iteration " + i + ": generated number must match 3-digit shape")
                    .matches("^GRP-\\d{3}$");
        }

        // Sanity: the resulting groups table has 20 rows, all
        // distinct registration numbers.
        Long distinct = db.sql("SELECT COUNT(DISTINCT registration_number)::bigint AS n FROM groups")
                .map(row -> row.get("n", Long.class))
                .one()
                .block(Duration.ofSeconds(10));
        assertThat(distinct)
                .as("all seeded + generated registration numbers must be distinct")
                .isEqualTo(20L);
    }
}

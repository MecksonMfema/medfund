package com.medfund.user.integration;

import com.medfund.shared.testfixtures.AbstractPostgresIntegrationTest;
import com.medfund.shared.testfixtures.TenantTestContext;
import com.medfund.shared.testfixtures.WithTenant;
import com.medfund.user.service.GroupNumberService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end IT for {@link GroupNumberService}. Covers the SQL seam
 * that the unit tests can't:
 *
 * <ul>
 *   <li>{@code loadScheme()} — reads
 *       {@code public.tenants.group_number_prefix / suffix /
 *       random_length}. Column renames, dropped migrations, or a bad
 *       {@code row.get} type would break the shape without any unit
 *       test firing.</li>
 *   <li>Custom tenant configs — a tenant with prefix=EMP-, suffix=-Z,
 *       length=8 must actually get numbers in that shape. Unit tests
 *       drive the format helper directly; this test drives it through
 *       the DB round-trip.</li>
 *   <li>Uniqueness retry with a live probe — seed a colliding row and
 *       assert the service picks a different value on retry.</li>
 * </ul>
 *
 * <p>Uses a stripped-down schema
 * ({@code test-resources/db/group-number-migration}) rather than the
 * production tenant migrations. Same rationale as the contributions-
 * service ITs: this module doesn't own the production migrations and
 * a slice test shouldn't couple to their evolution.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
    "spring.flyway.locations=classpath:db/group-number-migration",
    "spring.flyway.baseline-on-migrate=true",
})
@Import(GroupNumberServiceIT.SecurityStub.class)
class GroupNumberServiceIT extends AbstractPostgresIntegrationTest {

    /**
     * SecurityConfig requires a {@link ReactiveJwtDecoder}, normally
     * provided by the OAuth2 resource-server autoconfig fetching JWKS
     * from Keycloak. The IT doesn't run Keycloak, so we provide a
     * no-op decoder that yields a synthetic anonymous JWT. The test
     * doesn't hit any authenticated endpoints — the decoder only
     * needs to exist so the SecurityWebFilterChain bean can wire.
     */
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

    @Autowired private GroupNumberService groupNumberService;
    @Autowired private DatabaseClient db;

    /**
     * Reset the two seeded tables between tests so assertions stay
     * independent. Tenant row is re-seeded with the default shape;
     * individual tests can UPDATE it to drive custom-config paths.
     */
    @BeforeEach
    void resetSchema() {
        db.sql("TRUNCATE groups, tenants, scheduled_job_configs CASCADE").then().block();
        // Default shape: GRP-, no suffix, 6-digit random. Matches
        // the migration V125 defaults.
        db.sql("INSERT INTO tenants (id) VALUES (:id)")
                .bind("id", UUID.fromString(TENANT_ID))
                .then().block();
    }

    // ------------------------------------------------------------------
    // Default-shape sanity — proves the SQL round-trip works and the
    // resolver picks up the defaults from the columns.
    // ------------------------------------------------------------------

    @Test
    @WithTenant(TENANT_ID)
    void defaults_produceGrpDashSixDigits() {
        String number = block(groupNumberService.nextRegistrationNumber());
        assertThat(number).matches("^GRP-\\d{6}$");
    }

    // ------------------------------------------------------------------
    // Custom-config — the whole point of the feature. Regression here
    // would break every tenant that ever changed their number shape.
    // ------------------------------------------------------------------

    @Test
    @WithTenant(TENANT_ID)
    void customPrefixAndSuffix_areReflectedInGeneratedNumber() {
        setScheme("EMP-", "-Z", 6);

        String number = block(groupNumberService.nextRegistrationNumber());

        assertThat(number)
                .as("custom prefix + suffix must appear in the generated shape")
                .matches("^EMP-\\d{6}-Z$");
    }

    @Test
    @WithTenant(TENANT_ID)
    void customRandomLength_producesExactlyTheConfiguredWidth() {
        // Long tail (length=10) to catch regressions where int-vs-long
        // math for pow10 would silently truncate on wider values.
        setScheme("BIG-", "", 10);

        String number = block(groupNumberService.nextRegistrationNumber());

        assertThat(number).matches("^BIG-\\d{10}$");
    }

    @Test
    @WithTenant(TENANT_ID)
    void bareShapeWithoutPrefixOrSuffix_producesPlainDigits() {
        // Legal config — some tenants may want just digits without any
        // decoration. A regression that always prepended "GRP-" for
        // "empty" prefixes would surface here.
        setScheme("", "", 6);

        String number = block(groupNumberService.nextRegistrationNumber());

        assertThat(number).matches("^\\d{6}$");
    }

    // ------------------------------------------------------------------
    // Uniqueness retry — seed the collision candidate and prove the
    // service retries. Hard to write deterministically because the
    // RNG is uncontrolled, so we drive it via a length-3 scheme where
    // the candidate space (900 values) is small enough for a real
    // collision probability but big enough not to exhaust attempts.
    // ------------------------------------------------------------------

    @Test
    @WithTenant(TENANT_ID)
    void generatedNumber_neverCollidesWithExistingRow_evenInATightCandidateSpace() {
        // Length 3 = 900 candidates. Seed one and prove the returned
        // value is (a) shaped correctly and (b) NOT the seeded value.
        // Repeat 50 times — statistical chance the RNG hands us the
        // seeded value on all 50 attempts is (1/900)^50 ≈ 10⁻¹⁴⁷.
        setScheme("T-", "", 3);
        String seeded = "T-500";
        db.sql("INSERT INTO groups (id, name, registration_number) " +
                "VALUES (:id, 'Seed', :reg)")
                .bind("id", UUID.randomUUID())
                .bind("reg", seeded)
                .then().block();

        // Multiple draws — every one must dodge the seeded value AND
        // match the expected shape.
        for (int i = 0; i < 50; i++) {
            String number = block(groupNumberService.nextRegistrationNumber());
            assertThat(number)
                    .as("draw " + i + " must dodge the seeded collision")
                    .isNotEqualTo(seeded)
                    .matches("^T-\\d{3}$");
        }
    }

    // ------------------------------------------------------------------
    // No-tenant-context path — service falls back to defaults, but the
    // DB round-trip is skipped. Regression here would tie the fallback
    // path to a specific tenant row's config and break tests that run
    // without a reactor context.
    // ------------------------------------------------------------------

    @Test
    void noTenantContext_fallsBackToDefaults_withoutHittingDb() {
        // Deliberately no @WithTenant — the reactor context has no
        // tenant id. Alter the tenant row so the default and the
        // real row differ, then prove the returned number matches
        // the DEFAULTS (proving loadScheme short-circuited before the
        // SELECT). Uses a distinctive prefix so a bug that read the
        // row anyway would light up as an assertion mismatch.
        setScheme("SHOULD-NOT-APPEAR-", "", 6);

        String number = groupNumberService.nextRegistrationNumber()
                .block(Duration.ofSeconds(10));

        assertThat(number)
                .as("no tenant context → default 'GRP-' prefix, not the row's custom one")
                .matches("^GRP-\\d{6}$");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void setScheme(String prefix, String suffix, int length) {
        db.sql("""
                UPDATE tenants
                   SET group_number_prefix = :p,
                       group_number_suffix = :s,
                       group_number_random_length = :n
                 WHERE id = :id
                """)
                .bind("p", prefix)
                .bind("s", suffix)
                .bind("n", length)
                .bind("id", UUID.fromString(TENANT_ID))
                .then().block();
    }

    private <T> T block(Mono<T> mono) {
        return mono.contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(15));
    }
}

package com.medfund.user.integration;

import com.medfund.shared.testfixtures.TenantTestContext;
import com.medfund.user.service.KeycloakSyncService;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.user.publisher.PolicyStatusChangedPublisher;
import com.medfund.user.service.UserEventPublisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Shared base for the Phase 13 §A policy-lifecycle ITs. All concrete
 * subclasses intentionally share ONE dedicated Postgres container AND an
 * identical Spring context definition (same {@code @TestPropertySource},
 * same SecurityStub, same {@code @MockBean} set declared in each subclass)
 * so the context cache boots the app once and Flyway runs the
 * {@code db/policy-lifecycle-migration} baseline exactly once.
 *
 * <p>This base owns its OWN container rather than extending
 * {@code AbstractDedicatedPostgresIntegrationTest}: that class's static
 * container is already claimed by GroupServiceCreateIT, and two migration
 * families on one container collide on the Flyway version-1 checksum (see
 * the javadoc there — "one class per dedicated base").
 *
 * <p>Kafka collaborators ({@code AuditPublisher}, {@code UserEventPublisher})
 * and Keycloak ({@code KeycloakSyncService}) are mocked at the bean level —
 * the surface under test is the tenant-schema SQL seam, matching the
 * GroupServiceCreateIT precedent.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
    "spring.flyway.locations=classpath:db/policy-lifecycle-migration",
    "spring.flyway.baseline-on-migrate=true",
})
@Import(AbstractPolicyLifecycleIT.SecurityStub.class)
public abstract class AbstractPolicyLifecycleIT {

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

    protected static final String TENANT_ID = "00000000-0000-4000-8000-000000000042";
    protected static final String ACTOR_ID = "11111111-1111-4000-8000-000000000001";
    protected static final String ACTOR_EMAIL = "actor@test.example";

    @Autowired
    protected DatabaseClient db;

    protected UUID schemeId;

    /** Kafka + Keycloak stay out of the SQL-seam surface under test. */
    @MockBean private AuditPublisher auditPublisher;
    @MockBean private UserEventPublisher eventPublisher;
    @MockBean private KeycloakSyncService keycloakSyncService;
    @MockBean private PolicyStatusChangedPublisher policyStatusChangedPublisher;

    @org.junit.jupiter.api.BeforeEach
    void stubKafkaCollaborators() {
        // Unstubbed Mockito mocks return null Monos, which NPEs the
        // service chains. Lenient so tests that never publish don't trip
        // strict-stub checks.
        lenient().when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        lenient().when(eventPublisher.publishMemberLifecycle(
                        any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Mono.empty());
        lenient().when(eventPublisher.publishGroupLifecycle(
                        any(), any(), any(), any()))
                .thenReturn(Mono.empty());
        lenient().when(policyStatusChangedPublisher.publish(
                        any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Mono.empty());
    }

    /**
     * Reset every business table between tests. The tenants row is NOT
     * truncated — TenantAwareConnectionFactory resolves search_path from
     * public.tenants.schema_name ('public') per request.
     */
    @org.junit.jupiter.api.BeforeEach
    void resetPolicyLifecycleSchema() {
        db.sql("""
                TRUNCATE member_status_history, policy_status_history,
                         life_policies, funeral_policies, disability_policies,
                         travel_policies, vehicles, properties,
                         members, groups, schemes
                CASCADE
                """).then().block(Duration.ofSeconds(10));

        schemeId = UUID.randomUUID();
        db.sql("INSERT INTO schemes (id, name, insurance_line) VALUES (:id, 'IT Scheme', 'HEALTH')")
                .bind("id", schemeId)
                .then().block(Duration.ofSeconds(10));
    }

    protected UUID seedGroup(String name) {
        UUID groupId = UUID.randomUUID();
        // NOTE: id is bound explicitly so the returned handle IS the row's PK
        // (relying on the gen_random_uuid() default would return an id that
        // was never inserted).
        db.sql("INSERT INTO groups (id, name, status) VALUES (:id, :name, 'active')")
                .bind("id", groupId)
                .bind("name", name)
                .then().block(Duration.ofSeconds(10));
        return groupId;
    }

    /**
     * Seed a member satisfying the V026 tightened constraints (gender +
     * national_id + email + scheme_id NOT NULL, enrollment on a month start).
     */
    protected UUID seedMember(String number, String status, UUID groupId) {
        UUID memberId = UUID.randomUUID();
        var spec = db.sql("""
                INSERT INTO members (id, member_number, first_name, last_name, date_of_birth,
                                     gender, national_id, email, group_id, scheme_id,
                                     status, enrollment_date)
                VALUES (:id, :num, 'First', 'Last', '1980-01-01',
                        'male', :nat, :email, :gid, :sid,
                        :status, DATE '2026-01-01')
                """)
                .bind("id", memberId)
                .bind("num", number)
                .bind("nat", "NAT-" + number)
                .bind("email", number.toLowerCase() + "@it.example");
        if (groupId != null) {
            spec = spec.bind("gid", groupId);
        } else {
            spec = spec.bindNull("gid", UUID.class);
        }
        spec.bind("sid", schemeId)
                .bind("status", status)
                .then().block(Duration.ofSeconds(10));
        return memberId;
    }

    /** Seed an active life policy for the given member (V032 shape). */
    protected UUID seedLifePolicy(String number, UUID memberId, String status) {
        UUID policyId = UUID.randomUUID();
        db.sql("""
                INSERT INTO life_policies (id, scheme_id, insured_member_id, policy_number,
                                           sum_assured, term_months, status)
                VALUES (:id, :sid, :mid, :num, 100000.0000, 12, :status)
                """)
                .bind("id", policyId)
                .bind("sid", schemeId)
                .bind("mid", memberId)
                .bind("num", number)
                .bind("status", status)
                .then().block(Duration.ofSeconds(10));
        return policyId;
    }

    protected <T> T block(Mono<T> mono) {
        return mono.contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(15));
    }
}

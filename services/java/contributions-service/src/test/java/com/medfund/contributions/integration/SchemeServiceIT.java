package com.medfund.contributions.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.medfund.contributions.dto.CreateSchemeRequest;
import com.medfund.contributions.repository.SchemeRepository;
import com.medfund.contributions.service.SchemeService;
import com.medfund.shared.testfixtures.AbstractIntegrationTest;
import com.medfund.shared.testfixtures.TenantTestContext;
import com.medfund.shared.testfixtures.WithTenant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice-level integration test exercising the full SchemeService.create
 * pipeline: validation → R2DBC INSERT → audit envelope emitted to Kafka.
 *
 * <p>What this catches that unit tests can't:
 * <ul>
 *   <li>R2DBC binding bugs (column-name mismatch, type coercion, the recent
 *       null-{@code @Id} INSERT path)</li>
 *   <li>Audit envelope JSON shape — verified after the actual serialization /
 *       Kafka round-trip, not against a captured argument</li>
 *   <li>TenantContext propagation through {@code Mono.deferContextual}</li>
 * </ul>
 *
 * <p>Uses a stripped-down schema (test-migration/V001__schemes.sql) rather
 * than the tenant-side production migrations — the contributions module
 * does not own them, and Phase 3 deliberately stays a slice test, not a
 * full multi-tenant orchestration.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
    "spring.flyway.locations=classpath:db/test-migration",
    "spring.flyway.baseline-on-migrate=true"
})
@Import(SchemeServiceIT.SecurityStub.class)
class SchemeServiceIT extends AbstractIntegrationTest {

    /**
     * SecurityConfig requires a {@link ReactiveJwtDecoder}, normally provided by
     * the OAuth2 resource-server autoconfig fetching JWKS from Keycloak. The
     * IT doesn't run Keycloak, so we provide a no-op decoder that yields a
     * synthetic anonymous JWT. The test doesn't hit any authenticated
     * endpoints — the decoder only needs to exist so the SecurityWebFilterChain
     * bean can be wired.
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


    private static final String TENANT_ID = "00000000-0000-4000-8000-000000000001";

    @Autowired private SchemeService schemeService;
    @Autowired private SchemeRepository schemeRepository;

    @Test
    @WithTenant(TENANT_ID)
    void create_persistsRowAndEmitsAuditEventToKafka() {
        String uniqueName = "Gold IT " + UUID.randomUUID();
        var request = new CreateSchemeRequest(uniqueName, "desc", "hmo", "HEALTH",
            LocalDate.now(), null, "USD");

        // Capture the persisted ID so we can re-read and assert end-to-end.
        var actorId = UUID.randomUUID().toString();
        var actorEmail = "actor@test.example";
        var savedId = schemeService.create(request, actorId, actorEmail)
            .contextWrite(TenantTestContext.put())
            .map(s -> s.getId())
            .block(Duration.ofSeconds(10));

        assertThat(savedId).isNotNull();

        // Verify the row landed in Postgres with the expected currency normalization
        // (lower-case "USD" inputs are uppercased on the way in).
        StepVerifier.create(schemeRepository.findById(savedId))
            .assertNext(found -> {
                assertThat(found.getName()).isEqualTo(uniqueName);
                assertThat(found.getStatus()).isEqualTo("active");
                assertThat(found.getCurrencyCode()).isEqualTo("USD");
                assertThat(found.getSchemeType()).isEqualTo("hmo");
            })
            .verifyComplete();

        // Verify the audit envelope reached Kafka. The consumer reads from
        // earliest and filters by entityType so concurrent tests don't
        // confuse the assertion.
        JsonNode event = consumeAuditEvent("medfund.audit.events", "Scheme",
            Duration.ofSeconds(10));
        assertThat(event).as("audit event for Scheme mutation should reach Kafka").isNotNull();
        assertThat(event.path("action").asText()).isEqualTo("CREATE");
        assertThat(event.path("tenantId").asText()).isEqualTo(TENANT_ID);
        assertThat(event.path("actorId").asText()).isEqualTo(actorId);
        // The actorEmail slot must be populated end-to-end — UUID-only audit logs
        // force a DB join on every viewer. See AuditActor + coding-standards.md
        // "Actor identity — REQUIRED on every audit event".
        assertThat(event.path("actorEmail").asText())
            .as("actorEmail must reach Kafka so audit listings are human-scannable")
            .isEqualTo(actorEmail);
        assertThat(event.path("newValue").path("name").asText()).isEqualTo(uniqueName);
    }
}

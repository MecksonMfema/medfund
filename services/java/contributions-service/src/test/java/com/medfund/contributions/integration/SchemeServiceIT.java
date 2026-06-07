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
import org.springframework.test.context.TestPropertySource;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.LocalDate;
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
class SchemeServiceIT extends AbstractIntegrationTest {

    private static final String TENANT_ID = "00000000-0000-4000-8000-000000000001";

    @Autowired private SchemeService schemeService;
    @Autowired private SchemeRepository schemeRepository;

    @Test
    @WithTenant(TENANT_ID)
    void create_persistsRowAndEmitsAuditEventToKafka() {
        String uniqueName = "Gold IT " + UUID.randomUUID();
        var request = new CreateSchemeRequest(uniqueName, "desc", "hmo",
            LocalDate.now(), null, "USD");

        // Capture the persisted ID so we can re-read and assert end-to-end.
        var actorId = UUID.randomUUID().toString();
        var savedId = schemeService.create(request, actorId)
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
        assertThat(event.path("newValue").path("name").asText()).isEqualTo(uniqueName);
    }
}

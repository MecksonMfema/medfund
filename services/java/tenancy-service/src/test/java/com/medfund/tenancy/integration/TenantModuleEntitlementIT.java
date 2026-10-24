package com.medfund.tenancy.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.medfund.shared.entitlement.ModuleKey;
import com.medfund.shared.entitlement.TenantEntitlementRegistry;
import com.medfund.shared.testfixtures.AbstractIntegrationTest;
import com.medfund.tenancy.TenancyServiceApplication;
import com.medfund.tenancy.service.TenantModuleEntitlementService;
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

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end slice for the per-tenant module-entitlement seam (Plan A Phase 1):
 * a toggle upserts the row, the shared {@link TenantEntitlementRegistry} then
 * reads it back as entitled, an absent (tenant, module) reads as NOT entitled,
 * and the toggle emits all three cross-cutting signals — an audit event
 * (Rule 8), a {@code platform.module-entitlements.v1} broadcast (Rule 6), and
 * an {@code ENTITLEMENT_CHANGED} security event (Rule 9 / G5).
 *
 * <p>The broadcast + security-event payloads are the wire contract consumed by
 * the Java {@code EntitlementInvalidationConsumer} and the Go
 * {@code entitlements.Registry} — none of which are in tenancy-service's diff,
 * so a silent rename here would go unnoticed until the gate stopped propagating.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        classes = TenancyServiceApplication.class)
@TestPropertySource(properties = {
        "spring.flyway.locations=classpath:db/platform-settings-it-migration",
        "spring.flyway.table=flyway_schema_history_platform_settings_it",
        "spring.flyway.out-of-order=false"
})
@Import(TenantModuleEntitlementIT.SecurityStub.class)
class TenantModuleEntitlementIT extends AbstractIntegrationTest {

    private static final String ENTITLEMENT_TOPIC = "platform.module-entitlements.v1";
    private static final String AUDIT_TOPIC = "medfund.audit.events";
    private static final String SECURITY_TOPIC = "medfund.security.events";
    private static final String ACTOR_ID = "10000000-0000-4000-8000-000000000001";
    private static final String ACTOR_EMAIL = "admin@medfund.example";

    @Autowired private TenantModuleEntitlementService service;
    @Autowired private TenantEntitlementRegistry registry;

    @Test
    void toggleOn_persists_broadcasts_audits_andEmitsSecurityEvent() {
        UUID tenantId = UUID.randomUUID();

        var updated = service.setEntitlement(tenantId, ModuleKey.KYC.name(), true, ACTOR_ID, ACTOR_EMAIL)
                .block(Duration.ofSeconds(10));
        assertNotNull(updated);
        assertTrue(updated.enabled());

        // The shared registry reads the freshly-written row as entitled.
        assertTrue(Boolean.TRUE.equals(registry.isEntitled(tenantId, ModuleKey.KYC)
                .block(Duration.ofSeconds(10))));

        // Broadcast — field names are the contract (Go + Java consumers key off these).
        JsonNode event = consumeAuditEventMatching(ENTITLEMENT_TOPIC,
                n -> tenantId.toString().equals(n.path("tenantId").asText())
                        && "KYC".equals(n.path("moduleKey").asText()),
                Duration.ofSeconds(20));
        assertNotNull(event, "expected an entitlement event for " + tenantId + " on " + ENTITLEMENT_TOPIC);
        assertEquals("KYC", event.get("moduleKey").asText());
        assertTrue(event.get("enabled").asBoolean());
        assertEquals(ACTOR_EMAIL, event.get("actor").asText());
        assertFalse(event.get("updatedAt").asText().isBlank());

        // Audit (Rule 8) — friendly entityName, non-blank actorEmail.
        JsonNode audit = consumeAuditEventMatching(AUDIT_TOPIC,
                n -> "TENANT_MODULE_ENTITLEMENT".equals(n.path("entityType").asText())
                        && tenantId.toString().equals(n.path("tenantId").asText()),
                Duration.ofSeconds(20));
        assertNotNull(audit, "expected an audit event for the entitlement toggle");
        assertFalse(audit.path("actorEmail").asText().isBlank(), "actorEmail must be populated");
        assertFalse(audit.path("entityName").asText().isBlank(), "entityName must be friendly text");
        assertFalse(audit.path("entityName").asText().equals(tenantId.toString()),
                "entityName must not be the raw id");

        // Security event (Rule 9 / G5) — a privilege-level change.
        JsonNode security = consumeAuditEventMatching(SECURITY_TOPIC,
                n -> "ENTITLEMENT_CHANGED".equals(n.path("eventType").asText())
                        && tenantId.toString().equals(n.path("tenantId").asText()),
                Duration.ofSeconds(20));
        assertNotNull(security, "expected an ENTITLEMENT_CHANGED security event (G5)");
        assertEquals(ACTOR_EMAIL, security.path("actorEmail").asText());
        assertTrue(security.path("details").asText().contains("KYC"));
    }

    @Test
    void absentEntitlement_readsAsNotEntitled() {
        // A tenant with no row for this module — the fail-closed default.
        UUID stranger = UUID.randomUUID();
        Boolean entitled = registry.isEntitled(stranger, ModuleKey.KYC).block(Duration.ofSeconds(10));
        assertFalse(Boolean.TRUE.equals(entitled));
    }

    @TestConfiguration
    static class SecurityStub {
        @Bean
        ReactiveJwtDecoder reactiveJwtDecoder() {
            return token -> Mono.just(Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .claim("sub", ACTOR_ID)
                    .claim("email", ACTOR_EMAIL)
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(300))
                    .build());
        }
    }
}

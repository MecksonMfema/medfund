package com.medfund.tenancy.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.medfund.shared.flags.PlatformFlag;
import com.medfund.shared.testfixtures.AbstractIntegrationTest;
import com.medfund.tenancy.TenancyServiceApplication;
import com.medfund.tenancy.dto.UpdatePlatformSettingsRequest;
import com.medfund.tenancy.entity.PlatformSettings;
import com.medfund.tenancy.service.PlatformFeatureFlagService;
import com.medfund.tenancy.service.PlatformSettingsService;
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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for the platform-settings + platform-feature-flags service
 * layer. Verifies (a) V183 seed row is present, (b) update() persists and
 * emits an audit event tagged with the actor's email, (c) V184 + the
 * PlatformFlagSeeder produce one row per PlatformFlag enum value, (d) toggling
 * a flag persists and emits audit.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        classes = TenancyServiceApplication.class)
@TestPropertySource(properties = {
        // Own migration folder isolates this IT from V007+ scripts that other
        // ITs stack on classpath:db/test-migration. platform_settings has no
        // dependency on any other test table so we only need our own DDL.
        "spring.flyway.locations=classpath:db/platform-settings-it-migration",
        // Own history table, not just own location folder. The location folder
        // isolates WHICH scripts run, but every IT on the shared container still
        // writes the default flyway_schema_history; the platform-settings set
        // (V9101) and the db/test-migration set (V9001-V9006) then collide there
        // (out-of-order rejection on whichever migrates second). A distinct
        // history table fully decouples the two sets, order-independently.
        "spring.flyway.table=flyway_schema_history_platform_settings_it",
        "spring.flyway.out-of-order=false",
        // update() now best-effort syncs the Keycloak platform realm. Point it
        // at a dead port so a developer's `make infra` Keycloak on 9080 never
        // gets patched by a test run; KeycloakRealmSyncIT covers the sync.
        "keycloak.admin.url=http://localhost:1"
})
@Import(PlatformSettingsIT.SecurityStub.class)
class PlatformSettingsIT extends AbstractIntegrationTest {

    private static final String AUDIT_TOPIC = "medfund.audit.events";
    private static final String ACTOR_ID = "10000000-0000-4000-8000-000000000001";
    private static final String ACTOR_EMAIL = "admin@medfund.example";

    @Autowired
    private PlatformSettingsService settingsService;

    @Autowired
    private PlatformFeatureFlagService flagService;

    @Test
    void seedRowIsPresent() {
        PlatformSettings s = settingsService.get().block(Duration.ofSeconds(5));
        assertNotNull(s, "V183 must have inserted a singleton platform_settings row");
        assertEquals("MedFund", s.getPlatformName());
        assertEquals("ocean", s.getThemeTemplateId());
        assertEquals(Boolean.FALSE, s.getDarkMode());
        assertNull(s.getLogoBytes());
    }

    @Test
    void updatePersistsChangesAndEmitsAudit() {
        var req = new UpdatePlatformSettingsRequest(
                "MedFund Zimbabwe",
                "support@medfund.example",
                "midnight",
                Boolean.TRUE,
                "Health cover made easy",
                "Sign in to manage claims and contributions."
        );
        PlatformSettings saved = settingsService.update(req, ACTOR_ID, ACTOR_EMAIL)
                .block(Duration.ofSeconds(5));
        assertNotNull(saved);
        assertEquals("MedFund Zimbabwe", saved.getPlatformName());
        assertEquals("midnight", saved.getThemeTemplateId());
        assertEquals(Boolean.TRUE, saved.getDarkMode());
        assertEquals(ACTOR_EMAIL, saved.getUpdatedBy());

        // Same reasoning as the flag assertion below: pick out this test's own
        // event rather than the first PLATFORM_SETTINGS record on the topic.
        JsonNode event = consumeAuditEventMatching(AUDIT_TOPIC,
                node -> "PLATFORM_SETTINGS".equals(node.path("entityType").asText())
                        && "MedFund Zimbabwe".equals(node.path("newValue").path("platformName").asText()),
                Duration.ofSeconds(15));
        assertNotNull(event, "expected a PLATFORM_SETTINGS audit event on " + AUDIT_TOPIC);
        assertEquals(ACTOR_EMAIL, event.get("actorEmail").asText(),
                "audit event must carry actorEmail (feedback_audit_actor_email)");
        assertEquals("Platform settings", event.get("entityName").asText(),
                "audit event entityName must be friendly text, never UUID (feedback_audit_entity_name)");

        // Rule 8 requires the changed-field list, and it must name only the
        // fields that actually moved — `version` bumps on every save and
        // would otherwise appear in every single diff.
        List<String> changed = new ArrayList<>();
        event.get("changedFields").forEach(n -> changed.add(n.asText()));
        assertTrue(changed.contains("themeTemplateId"), "expected themeTemplateId in " + changed);
        assertTrue(changed.contains("darkMode"), "expected darkMode in " + changed);
        assertFalse(changed.contains("version"), "version must be excluded from " + changed);
    }

    @Test
    void flagsSeededOnStartup() {
        List<String> keys = flagService.list()
                .map(r -> r.key())
                .collectList()
                .block(Duration.ofSeconds(5));
        assertNotNull(keys);
        for (PlatformFlag flag : PlatformFlag.values()) {
            assertTrue(keys.contains(flag.name()),
                    "PlatformFlagSeeder must insert a row for every enum value; missing " + flag.name());
        }
    }

    @Test
    void toggleFlagPersistsAndEmitsAudit() {
        var updated = flagService.update("AI_ADJUDICATION", true, ACTOR_ID, ACTOR_EMAIL)
                .block(Duration.ofSeconds(5));
        assertNotNull(updated);
        assertEquals(Boolean.TRUE, updated.enabled());
        assertEquals(ACTOR_EMAIL, updated.updatedBy());

        // Filter on entityId, not just entityType: FlagBroadcastIT shares this
        // JVM's Kafka container and also publishes PLATFORM_FEATURE_FLAG audit
        // events, so "first record of this type" is whichever class ran first.
        JsonNode event = consumeAuditEventMatching(AUDIT_TOPIC,
                node -> "PLATFORM_FEATURE_FLAG".equals(node.path("entityType").asText())
                        && "AI_ADJUDICATION".equals(node.path("entityId").asText()),
                Duration.ofSeconds(15));
        assertNotNull(event, "expected an AI_ADJUDICATION audit event on " + AUDIT_TOPIC);
        assertEquals(ACTOR_EMAIL, event.get("actorEmail").asText());
        assertEquals("AI_ADJUDICATION", event.get("entityId").asText());
        assertEquals(PlatformFlag.AI_ADJUDICATION.displayName(), event.get("entityName").asText(),
                "audit event entityName must be friendly text, never the raw key "
                        + "(feedback_audit_entity_name)");
        assertTrue(event.get("newValue").get("enabled").asBoolean());
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

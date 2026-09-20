package com.medfund.tenancy.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.medfund.shared.flags.PlatformFlag;
import com.medfund.shared.testfixtures.AbstractIntegrationTest;
import com.medfund.tenancy.TenancyServiceApplication;
import com.medfund.tenancy.service.PlatformFeatureFlagService;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the {@code platform.feature-flags.v1} wire contract. Every Java service
 * (via {@code FlagInvalidationConsumer}) and every Go service (via
 * {@code shared/flags.Registry}) parses this payload, and none of those files
 * are in tenancy-service's diff — so a silent rename here would go unnoticed
 * until a flag toggle stopped propagating in production.
 *
 * <p>The topic is additive-only: fields may be added, never removed or
 * renamed without a {@code v2} topic.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        classes = TenancyServiceApplication.class)
@TestPropertySource(properties = {
        // Same isolated folder as PlatformSettingsIT: the flags table has no
        // dependency on any other test fixture's DDL.
        "spring.flyway.locations=classpath:db/platform-settings-it-migration",
        "spring.flyway.out-of-order=false"
})
@Import(FlagBroadcastIT.SecurityStub.class)
class FlagBroadcastIT extends AbstractIntegrationTest {

    private static final String FLAG_TOPIC = "platform.feature-flags.v1";
    private static final String ACTOR_ID = "10000000-0000-4000-8000-000000000001";
    private static final String ACTOR_EMAIL = "admin@medfund.example";

    @Autowired
    private PlatformFeatureFlagService flagService;

    @Test
    void toggleOnBroadcastsTheFlagChange() {
        var updated = flagService.update(PlatformFlag.FRAUD_DETECTION.name(), true, ACTOR_ID, ACTOR_EMAIL)
                .block(Duration.ofSeconds(10));
        assertNotNull(updated);
        assertTrue(updated.enabled());

        JsonNode event = awaitFlagEvent(PlatformFlag.FRAUD_DETECTION.name(), true);
        assertNotNull(event, "expected an enabled=true event for FRAUD_DETECTION on " + FLAG_TOPIC);

        // Field names are the contract: Go's flagRow and the Java consumer
        // both key off "key" and "enabled".
        assertEquals(PlatformFlag.FRAUD_DETECTION.name(), event.get("key").asText());
        assertTrue(event.get("enabled").asBoolean());
        assertEquals(ACTOR_EMAIL, event.get("actor").asText());
        assertFalse(event.get("updatedAt").asText().isBlank(),
                "updatedAt must be populated so consumers can order or log the change");
    }

    @Test
    void toggleOffBroadcastsEnabledFalse() {
        // The off direction matters as much as the on direction: a consumer
        // that only reacted to enabled=true would leave a feature stuck on.
        flagService.update(PlatformFlag.GROUP_PORTAL.name(), true, ACTOR_ID, ACTOR_EMAIL)
                .block(Duration.ofSeconds(10));
        var disabled = flagService.update(PlatformFlag.GROUP_PORTAL.name(), false, ACTOR_ID, ACTOR_EMAIL)
                .block(Duration.ofSeconds(10));
        assertNotNull(disabled);
        assertFalse(disabled.enabled());

        JsonNode event = awaitFlagEvent(PlatformFlag.GROUP_PORTAL.name(), false);
        assertNotNull(event, "expected an enabled=false event for GROUP_PORTAL");
        assertEquals(PlatformFlag.GROUP_PORTAL.name(), event.get("key").asText());
        assertFalse(event.get("enabled").asBoolean());
    }

    /**
     * Matches on key + state rather than taking the first record: both tests
     * in this class publish to the same topic on a Kafka container shared by
     * every IT in the JVM, so "the first flag event" is a race.
     */
    private JsonNode awaitFlagEvent(String key, boolean enabled) {
        return consumeAuditEventMatching(FLAG_TOPIC,
                node -> key.equals(node.path("key").asText())
                        && node.path("enabled").asBoolean() == enabled,
                Duration.ofSeconds(20));
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

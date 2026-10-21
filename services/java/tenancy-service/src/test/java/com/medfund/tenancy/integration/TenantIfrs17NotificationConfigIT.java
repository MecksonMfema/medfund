package com.medfund.tenancy.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.shared.testfixtures.AbstractIntegrationTest;
import com.medfund.shared.testfixtures.TenantTestContext;
import com.medfund.shared.testfixtures.WithTenant;
import com.medfund.tenancy.TenancyServiceApplication;
import com.medfund.tenancy.dto.AddTenantIfrs17NotificationConfigRequest;
import com.medfund.tenancy.dto.UpdateTenantIfrs17NotificationConfigRequest;
import com.medfund.tenancy.entity.TenantIfrs17NotificationConfig;
import com.medfund.tenancy.service.TenantIfrs17NotificationConfigService;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Service-level integration tests for the Phase 15 §19 IFRS 17 notification
 * config CRUD surface. Boots the full tenancy-service context against the
 * shared Postgres + Kafka containers with the {@code db/test-migration}
 * schema (V001..V004), then drives {@link TenantIfrs17NotificationConfigService}
 * directly.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        classes = TenancyServiceApplication.class)
@TestPropertySource(properties = {
        "spring.flyway.locations=classpath:db/test-migration",
        "spring.flyway.out-of-order=false"
})
@Import(TenantIfrs17NotificationConfigIT.SecurityStub.class)
@WithTenant("00000000-0000-4000-8000-000000000001")
class TenantIfrs17NotificationConfigIT extends AbstractIntegrationTest {

    private static final String ENTITY_TYPE = "TENANT_IFRS17_NOTIFICATION_CONFIG";
    private static final String AUDIT_TOPIC = "medfund.audit.events";
    private static final String ACTOR_ID = "10000000-0000-4000-8000-000000000001";
    private static final String ACTOR_EMAIL = "admin@medfund.example";

    public static final String TENANT_ID = "00000000-0000-4000-8000-000000000001";
    public static final String OTHER_TENANT = "00000000-0000-4000-8000-000000000099";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private TenantIfrs17NotificationConfigService service;

    @Autowired
    private R2dbcEntityTemplate r2dbcTemplate;

    @BeforeEach
    void wipeRows() {
        r2dbcTemplate.getDatabaseClient()
                .sql("DELETE FROM tenant_ifrs17_notification_config")
                .then()
                .block(Duration.ofSeconds(10));
    }

    @Test
    void list_emptyForFreshTenant() {
        List<TenantIfrs17NotificationConfig> rows = service.list(uuid(TENANT_ID))
                .collectList()
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(10));
        assertNotNull(rows);
        assertTrue(rows.isEmpty());
    }

    @Test
    void addEmail_insertsAndAuditsCreate() {
        TenantIfrs17NotificationConfig saved = addEmail(
                TENANT_ID, "ONEROUS_TRANSITION", "risk-team@medfund.example", 15);

        assertNotNull(saved.getId());
        assertEquals("ONEROUS_TRANSITION", saved.getEventType());
        assertEquals("EMAIL", saved.getDeliveryMethod());
        assertEquals("risk-team@medfund.example", saved.getRecipient());
        assertEquals(15, saved.getThrottleMinutes());
        assertTrue(saved.getIsActive());

        JsonNode event = auditEventFor("CREATE", saved.getId(), Duration.ofSeconds(10));
        assertEquals(ENTITY_TYPE, event.get("entityType").asText());
        assertTrue(event.get("entityName").asText()
                        .startsWith("Ifrs17NotificationConfig for tenant it ONEROUS_TRANSITION → risk-team@medfund.example"),
                "entityName should name tenant slug + event type + recipient: "
                        + event.get("entityName").asText());
        assertTrue(event.get("entityName").asText().contains("(EMAIL)"),
                "entityName should carry the delivery method: " + event.get("entityName").asText());
        assertEquals(TENANT_ID, event.get("tenantId").asText());
        assertEquals(ACTOR_EMAIL, event.get("actorEmail").asText());
    }

    @Test
    void addWebhook_insertsForUrlRecipient() {
        TenantIfrs17NotificationConfig saved = service.add(uuid(TENANT_ID),
                        new AddTenantIfrs17NotificationConfigRequest(
                                "CSM_NEGATIVE", "WEBHOOK",
                                "https://ops.medfund.example/hooks/ifrs17",
                                30, true),
                        ACTOR_ID, ACTOR_EMAIL)
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(10));

        assertNotNull(saved);
        assertEquals("WEBHOOK", saved.getDeliveryMethod());
        assertEquals("https://ops.medfund.example/hooks/ifrs17", saved.getRecipient());
        assertEquals(30, saved.getThrottleMinutes());
    }

    @Test
    void update_mutatesChannelAndAuditsUpdate() {
        TenantIfrs17NotificationConfig saved = addEmail(
                TENANT_ID, "ONEROUS_TRANSITION", "risk-team@medfund.example", 15);

        TenantIfrs17NotificationConfig updated = service.update(
                        uuid(TENANT_ID), saved.getId(),
                        new UpdateTenantIfrs17NotificationConfigRequest(
                                "BOTH", 60, false),
                        ACTOR_ID, ACTOR_EMAIL)
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(10));

        assertNotNull(updated);
        assertEquals("BOTH", updated.getDeliveryMethod());
        assertEquals(60, updated.getThrottleMinutes());
        assertEquals(Boolean.FALSE, updated.getIsActive());
        // Tuple key preserved
        assertEquals("ONEROUS_TRANSITION", updated.getEventType());
        assertEquals("risk-team@medfund.example", updated.getRecipient());

        JsonNode event = auditEventFor("UPDATE", updated.getId(), Duration.ofSeconds(10));
        List<String> changed = new ArrayList<>();
        for (JsonNode field : event.get("changedFields")) {
            changed.add(field.asText());
        }
        assertTrue(changed.contains("deliveryMethod"), "changedFields should name deliveryMethod: " + changed);
        assertTrue(changed.contains("throttleMinutes"), "changedFields should name throttleMinutes: " + changed);
        assertTrue(changed.contains("isActive"), "changedFields should name isActive: " + changed);
    }

    @Test
    void delete_removesAndAuditsDelete() {
        TenantIfrs17NotificationConfig saved = addEmail(
                TENANT_ID, "IBNR_SUB_JOB_STALE", "actuarial@medfund.example", 15);

        service.delete(uuid(TENANT_ID), saved.getId(), ACTOR_ID, ACTOR_EMAIL)
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(10));

        List<TenantIfrs17NotificationConfig> remaining = service.list(uuid(TENANT_ID))
                .collectList()
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(10));
        assertTrue(remaining.isEmpty());

        JsonNode event = auditEventFor("DELETE", saved.getId(), Duration.ofSeconds(10));
        assertEquals("IBNR_SUB_JOB_STALE", event.get("oldValue").get("eventType").asText());
        JsonNode newValue = event.get("newValue");
        assertTrue(newValue == null || newValue.isNull(), "newValue should be null on DELETE");
    }

    @Test
    void update_rejectsCrossTenantRow() {
        TenantIfrs17NotificationConfig saved = addEmail(
                TENANT_ID, "ONEROUS_TRANSITION", "risk-team@medfund.example", 15);

        assertThrows(IllegalArgumentException.class, () ->
                service.update(uuid(OTHER_TENANT), saved.getId(),
                                new UpdateTenantIfrs17NotificationConfigRequest(
                                        "EMAIL", 30, true),
                                ACTOR_ID, ACTOR_EMAIL)
                        .contextWrite(TenantTestContext.put())
                        .block(Duration.ofSeconds(10)));
    }

    @Test
    void delete_rejectsCrossTenantRow() {
        TenantIfrs17NotificationConfig saved = addEmail(
                TENANT_ID, "ONEROUS_TRANSITION", "risk-team@medfund.example", 15);

        assertThrows(IllegalArgumentException.class, () ->
                service.delete(uuid(OTHER_TENANT), saved.getId(), ACTOR_ID, ACTOR_EMAIL)
                        .contextWrite(TenantTestContext.put())
                        .block(Duration.ofSeconds(10)));
    }

    @Test
    void add_rejectsUnknownEventType() {
        assertThrows(IllegalArgumentException.class, () ->
                service.add(uuid(TENANT_ID),
                                new AddTenantIfrs17NotificationConfigRequest(
                                        "MYSTERY_EVENT", "EMAIL", "x@medfund.example", 15, true),
                                ACTOR_ID, ACTOR_EMAIL)
                        .contextWrite(TenantTestContext.put())
                        .block(Duration.ofSeconds(10)));
    }

    @Test
    void add_rejectsUnknownDeliveryMethod() {
        assertThrows(IllegalArgumentException.class, () ->
                service.add(uuid(TENANT_ID),
                                new AddTenantIfrs17NotificationConfigRequest(
                                        "ONEROUS_TRANSITION", "SMS", "+2637000000", 15, true),
                                ACTOR_ID, ACTOR_EMAIL)
                        .contextWrite(TenantTestContext.put())
                        .block(Duration.ofSeconds(10)));
    }

    @Test
    void add_rejectsEmailWithoutAt() {
        assertThrows(IllegalArgumentException.class, () ->
                service.add(uuid(TENANT_ID),
                                new AddTenantIfrs17NotificationConfigRequest(
                                        "ONEROUS_TRANSITION", "EMAIL", "risk-team-medfund.example", 15, true),
                                ACTOR_ID, ACTOR_EMAIL)
                        .contextWrite(TenantTestContext.put())
                        .block(Duration.ofSeconds(10)));
    }

    @Test
    void add_rejectsWebhookWithoutScheme() {
        assertThrows(IllegalArgumentException.class, () ->
                service.add(uuid(TENANT_ID),
                                new AddTenantIfrs17NotificationConfigRequest(
                                        "CSM_NEGATIVE", "WEBHOOK", "ops.medfund.example/hooks/x", 15, true),
                                ACTOR_ID, ACTOR_EMAIL)
                        .contextWrite(TenantTestContext.put())
                        .block(Duration.ofSeconds(10)));
    }

    @Test
    void activeFor_returnsMatchingPlusAllWildcard() {
        // Two rows: one specific, one ALL wildcard, one inactive that shouldn't appear.
        addEmail(TENANT_ID, "ONEROUS_TRANSITION", "specific@medfund.example", 15);
        addEmail(TENANT_ID, "ALL", "everything@medfund.example", 60);
        TenantIfrs17NotificationConfig inactive = addEmail(
                TENANT_ID, "ONEROUS_TRANSITION", "muted@medfund.example", 15);
        service.update(uuid(TENANT_ID), inactive.getId(),
                        new UpdateTenantIfrs17NotificationConfigRequest("EMAIL", 15, false),
                        ACTOR_ID, ACTOR_EMAIL)
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(10));

        List<TenantIfrs17NotificationConfig> active =
                service.activeFor(uuid(TENANT_ID), "ONEROUS_TRANSITION")
                        .collectList()
                        .contextWrite(TenantTestContext.put())
                        .block(Duration.ofSeconds(10));

        assertNotNull(active);
        assertEquals(2, active.size(),
                "should return the specific row plus the ALL wildcard, not the muted one");
        List<String> recipients = active.stream().map(TenantIfrs17NotificationConfig::getRecipient).toList();
        assertTrue(recipients.contains("specific@medfund.example"));
        assertTrue(recipients.contains("everything@medfund.example"));
    }

    private TenantIfrs17NotificationConfig addEmail(String tenantId, String eventType,
                                                    String recipient, int throttle) {
        return service.add(uuid(tenantId),
                        new AddTenantIfrs17NotificationConfigRequest(
                                eventType, "EMAIL", recipient, throttle, true),
                        ACTOR_ID, ACTOR_EMAIL)
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(10));
    }

    private JsonNode auditEventFor(String action, UUID entityId, Duration timeout) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "audit-assert-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");

        String entityIdStr = entityId.toString();
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(AUDIT_TOPIC));
            while (System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> rec : records) {
                    try {
                        JsonNode node = MAPPER.readTree(rec.value());
                        if (ENTITY_TYPE.equals(node.path("entityType").asText())
                                && action.equals(node.path("action").asText())
                                && entityIdStr.equals(node.path("entityId").asText())) {
                            return node;
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        throw new AssertionError("no " + ENTITY_TYPE + "/" + action + "/" + entityIdStr
                + " audit event within " + timeout);
    }

    private static UUID uuid(String s) {
        return UUID.fromString(s);
    }

    @TestConfiguration
    static class SecurityStub {
        @Bean
        ReactiveJwtDecoder reactiveJwtDecoder() {
            return token -> Mono.just(Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .claim("sub", "it-user")
                    .claim("email", "reports-it@medfund.example")
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(300))
                    .build());
        }
    }
}

package com.medfund.tenancy.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.shared.testfixtures.AbstractIntegrationTest;
import com.medfund.shared.testfixtures.TenantTestContext;
import com.medfund.shared.testfixtures.WithTenant;
import com.medfund.tenancy.TenancyServiceApplication;
import com.medfund.tenancy.dto.AddTenantPersistencyBasisRequest;
import com.medfund.tenancy.dto.UpdateTenantPersistencyBasisRequest;
import com.medfund.tenancy.entity.TenantPersistencyBasis;
import com.medfund.tenancy.service.TenantPersistencyBasisService;
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

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Service-level integration tests for the Phase 14 §2 persistency basis
 * CRUD surface. Boots the full tenancy-service context against the shared
 * Postgres + Kafka containers with the {@code db/actuarial-phase2-migration}
 * schema, then drives {@link TenantPersistencyBasisService} directly:
 *
 * <ul>
 *   <li>list is empty for a fresh tenant</li>
 *   <li>add inserts a row, returns it, and emits a CREATE audit event whose
 *       {@code entityName} names the tenant slug + line + cohort
 *       (feedback_audit_entity_name)</li>
 *   <li>update mutates only the mutable fields (retention pct, source note,
 *       effective_to) and emits an UPDATE audit event listing the changed
 *       fields</li>
 *   <li>delete removes the row and emits a DELETE audit event</li>
 *   <li>Rule-2: attempts to update/delete a row belonging to a different
 *       tenant reject with IllegalArgumentException</li>
 * </ul>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        classes = TenancyServiceApplication.class)
@TestPropertySource(properties = {
        "spring.flyway.locations=classpath:db/test-migration",
        "spring.flyway.out-of-order=false"
})
@Import(TenantPersistencyBasisIT.SecurityStub.class)
@WithTenant("00000000-0000-4000-8000-000000000001")
class TenantPersistencyBasisIT extends AbstractIntegrationTest {

    private static final String ENTITY_TYPE = "TENANT_PERSISTENCY_BASIS";
    private static final String AUDIT_TOPIC = "medfund.audit.events";
    private static final String ACTOR_ID = "10000000-0000-4000-8000-000000000001";
    private static final String ACTOR_EMAIL = "admin@medfund.example";

    public static final String TENANT_ID = "00000000-0000-4000-8000-000000000001";
    public static final String OTHER_TENANT = "00000000-0000-4000-8000-000000000099";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private TenantPersistencyBasisService service;

    @Autowired
    private R2dbcEntityTemplate r2dbcTemplate;

    @BeforeEach
    void wipeRows() {
        r2dbcTemplate.getDatabaseClient()
                .sql("DELETE FROM tenant_persistency_basis")
                .then()
                .block(Duration.ofSeconds(10));
    }

    @Test
    void list_emptyForFreshTenant() {
        List<TenantPersistencyBasis> rows = service.list(uuid(TENANT_ID))
                .collectList()
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(10));
        assertNotNull(rows);
        assertTrue(rows.isEmpty(), "no rows expected after wipe");
    }

    @Test
    void add_insertsAndAuditsCreate() {
        TenantPersistencyBasis saved = add(TENANT_ID, "HEALTH", 12, "0.75", "industry_default_v1");

        assertNotNull(saved.getId());
        assertEquals("HEALTH", saved.getInsuranceLine());
        assertEquals(12, saved.getCohortMonths());
        assertEquals(0, new BigDecimal("0.7500").compareTo(saved.getExpectedRetentionPct()));

        JsonNode event = auditEventFor("CREATE", saved.getId(), Duration.ofSeconds(10));
        assertEquals(ENTITY_TYPE, event.get("entityType").asText());
        assertTrue(event.get("entityName").asText().startsWith("PersistencyBasis for tenant it "),
                "entityName should name tenant slug + line + cohort: " + event.get("entityName").asText());
        assertEquals(TENANT_ID, event.get("tenantId").asText());
        assertEquals(ACTOR_EMAIL, event.get("actorEmail").asText());
        assertEquals("HEALTH", event.get("newValue").get("insuranceLine").asText());
    }

    @Test
    void update_mutatesMutableFieldsAndAuditsUpdate() {
        TenantPersistencyBasis saved = add(TENANT_ID, "LIFE", 24, "0.85", "industry_default_v1");

        TenantPersistencyBasis updated = service.update(
                        uuid(TENANT_ID), saved.getId(),
                        new UpdateTenantPersistencyBasisRequest(
                                new BigDecimal("0.9000"),
                                "revised after 2026 review",
                                LocalDate.of(2027, 12, 31)),
                        ACTOR_ID, ACTOR_EMAIL)
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(10));

        assertNotNull(updated);
        assertEquals(0, new BigDecimal("0.9000").compareTo(updated.getExpectedRetentionPct()));
        assertEquals("revised after 2026 review", updated.getSourceNote());
        assertEquals(LocalDate.of(2027, 12, 31), updated.getEffectiveTo());
        // Immutable key preserved
        assertEquals("LIFE", updated.getInsuranceLine());
        assertEquals(24, updated.getCohortMonths());

        JsonNode event = auditEventFor("UPDATE", updated.getId(), Duration.ofSeconds(10));
        assertEquals(ENTITY_TYPE, event.get("entityType").asText());
        List<String> changed = new ArrayList<>();
        for (JsonNode field : event.get("changedFields")) {
            changed.add(field.asText());
        }
        assertTrue(changed.contains("expectedRetentionPct"), "changedFields should name expectedRetentionPct: " + changed);
        assertTrue(changed.contains("sourceNote"), "changedFields should name sourceNote: " + changed);
        assertTrue(changed.contains("effectiveTo"), "changedFields should name effectiveTo: " + changed);
    }

    @Test
    void delete_removesAndAuditsDelete() {
        TenantPersistencyBasis saved = add(TENANT_ID, "FUNERAL", 36, "0.70", null);

        service.delete(uuid(TENANT_ID), saved.getId(), ACTOR_ID, ACTOR_EMAIL)
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(10));

        List<TenantPersistencyBasis> remaining = service.list(uuid(TENANT_ID))
                .collectList()
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(10));
        assertTrue(remaining.isEmpty(), "row should be gone after delete");

        JsonNode event = auditEventFor("DELETE", saved.getId(), Duration.ofSeconds(10));
        assertEquals(ENTITY_TYPE, event.get("entityType").asText());
        // DELETE keeps oldValue and nulls newValue
        assertEquals("FUNERAL", event.get("oldValue").get("insuranceLine").asText());
        JsonNode newValue = event.get("newValue");
        assertTrue(newValue == null || newValue.isNull(), "newValue should be null on DELETE");
    }

    @Test
    void update_rejectsCrossTenantRow() {
        TenantPersistencyBasis saved = add(TENANT_ID, "HEALTH", 6, "0.85", null);

        assertThrows(IllegalArgumentException.class, () ->
                service.update(uuid(OTHER_TENANT), saved.getId(),
                                new UpdateTenantPersistencyBasisRequest(
                                        new BigDecimal("0.9000"), "attempted hijack", null),
                                ACTOR_ID, ACTOR_EMAIL)
                        .contextWrite(TenantTestContext.put())
                        .block(Duration.ofSeconds(10)));
    }

    @Test
    void delete_rejectsCrossTenantRow() {
        TenantPersistencyBasis saved = add(TENANT_ID, "HEALTH", 3, "0.90", null);

        assertThrows(IllegalArgumentException.class, () ->
                service.delete(uuid(OTHER_TENANT), saved.getId(), ACTOR_ID, ACTOR_EMAIL)
                        .contextWrite(TenantTestContext.put())
                        .block(Duration.ofSeconds(10)));
    }

    @Test
    void add_rejectsUnknownInsuranceLine() {
        assertThrows(IllegalArgumentException.class, () ->
                service.add(uuid(TENANT_ID),
                                new AddTenantPersistencyBasisRequest(
                                        "MADE_UP_LINE", 12, new BigDecimal("0.5000"), null, null, null),
                                ACTOR_ID, ACTOR_EMAIL)
                        .contextWrite(TenantTestContext.put())
                        .block(Duration.ofSeconds(10)));
    }

    private TenantPersistencyBasis add(String tenantId, String line, int cohortMonths,
                                       String pct, String sourceNote) {
        return service.add(uuid(tenantId),
                        new AddTenantPersistencyBasisRequest(
                                line, cohortMonths, new BigDecimal(pct),
                                sourceNote, null, null),
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

package com.medfund.tenancy.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.shared.testfixtures.AbstractIntegrationTest;
import com.medfund.shared.testfixtures.TenantTestContext;
import com.medfund.shared.testfixtures.WithTenant;
import com.medfund.tenancy.TenancyServiceApplication;
import com.medfund.tenancy.dto.AddTenantMorbidityBasisRequest;
import com.medfund.tenancy.dto.UpdateTenantMorbidityBasisRequest;
import com.medfund.tenancy.entity.TenantMorbidityBasis;
import com.medfund.tenancy.service.TenantMorbidityBasisService;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Service-level integration tests for the Phase 14 §2 morbidity basis CRUD
 * surface. Mirrors {@link TenantMortalityBasisIT}; only the entity type + column
 * name (morbidity_multiplier) differ.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        classes = TenancyServiceApplication.class)
@TestPropertySource(properties = {
        "spring.flyway.locations=classpath:db/test-migration",
        "spring.flyway.out-of-order=false"
})
@Import(TenantMorbidityBasisIT.SecurityStub.class)
@WithTenant("00000000-0000-4000-8000-000000000001")
class TenantMorbidityBasisIT extends AbstractIntegrationTest {

    private static final String ENTITY_TYPE = "TENANT_MORBIDITY_BASIS";
    private static final String AUDIT_TOPIC = "medfund.audit.events";
    private static final String ACTOR_ID = "10000000-0000-4000-8000-000000000001";
    private static final String ACTOR_EMAIL = "admin@medfund.example";

    public static final String TENANT_ID = "00000000-0000-4000-8000-000000000001";
    public static final String OTHER_TENANT = "00000000-0000-4000-8000-000000000099";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private TenantMorbidityBasisService service;

    @Autowired
    private R2dbcEntityTemplate r2dbcTemplate;

    @BeforeEach
    void wipeRows() {
        r2dbcTemplate.getDatabaseClient()
                .sql("DELETE FROM tenant_morbidity_basis")
                .then()
                .block(Duration.ofSeconds(10));
    }

    @Test
    void add_insertsAndAuditsCreate() {
        TenantMorbidityBasis saved = add(TENANT_ID, "HEALTH", "CIDA", "1.0500");

        assertNotNull(saved.getId());
        assertEquals("HEALTH", saved.getInsuranceLine());
        assertEquals("CIDA", saved.getBasisName());
        assertEquals(0, new BigDecimal("1.0500").compareTo(saved.getMorbidityMultiplier()));

        JsonNode event = auditEventFor("CREATE", saved.getId(), Duration.ofSeconds(10));
        assertEquals(ENTITY_TYPE, event.get("entityType").asText());
        assertTrue(event.get("entityName").asText().startsWith("MorbidityBasis for tenant it "),
                "entityName should name tenant slug + line + basis: " + event.get("entityName").asText());
        assertEquals(ACTOR_EMAIL, event.get("actorEmail").asText());
        assertEquals("CIDA", event.get("newValue").get("basisName").asText());
    }

    @Test
    void update_mutatesMutableFieldsAndAuditsUpdate() {
        TenantMorbidityBasis saved = add(TENANT_ID, "HEALTH", "CIDA", "1.0500");

        TenantMorbidityBasis updated = service.update(
                        uuid(TENANT_ID), saved.getId(),
                        new UpdateTenantMorbidityBasisRequest("GLTD87", new BigDecimal("1.1000"), null),
                        ACTOR_ID, ACTOR_EMAIL)
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(10));

        assertNotNull(updated);
        assertEquals("GLTD87", updated.getBasisName());
        assertEquals(0, new BigDecimal("1.1000").compareTo(updated.getMorbidityMultiplier()));

        JsonNode event = auditEventFor("UPDATE", updated.getId(), Duration.ofSeconds(10));
        List<String> changed = new ArrayList<>();
        for (JsonNode field : event.get("changedFields")) {
            changed.add(field.asText());
        }
        assertTrue(changed.contains("basisName"), "changedFields should name basisName: " + changed);
        assertTrue(changed.contains("morbidityMultiplier"), "changedFields should name morbidityMultiplier: " + changed);
    }

    @Test
    void delete_removesAndAuditsDelete() {
        TenantMorbidityBasis saved = add(TENANT_ID, "DISABILITY", "GLTD87", "1.0000");

        service.delete(uuid(TENANT_ID), saved.getId(), ACTOR_ID, ACTOR_EMAIL)
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(10));

        List<TenantMorbidityBasis> remaining = service.list(uuid(TENANT_ID))
                .collectList()
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(10));
        assertTrue(remaining.isEmpty(), "row should be gone after delete");

        JsonNode event = auditEventFor("DELETE", saved.getId(), Duration.ofSeconds(10));
        assertEquals("GLTD87", event.get("oldValue").get("basisName").asText());
        JsonNode newValue = event.get("newValue");
        assertTrue(newValue == null || newValue.isNull(), "newValue should be null on DELETE");
    }

    @Test
    void update_rejectsCrossTenantRow() {
        TenantMorbidityBasis saved = add(TENANT_ID, "HEALTH", "CIDA", "1.0000");
        assertThrows(IllegalArgumentException.class, () ->
                service.update(uuid(OTHER_TENANT), saved.getId(),
                                new UpdateTenantMorbidityBasisRequest("GLTD87", new BigDecimal("1.0000"), null),
                                ACTOR_ID, ACTOR_EMAIL)
                        .contextWrite(TenantTestContext.put())
                        .block(Duration.ofSeconds(10)));
    }

    @Test
    void add_rejectsUnknownInsuranceLine() {
        assertThrows(IllegalArgumentException.class, () ->
                service.add(uuid(TENANT_ID),
                                new AddTenantMorbidityBasisRequest(
                                        "MADE_UP", "CIDA", new BigDecimal("1.0000"), null, null),
                                ACTOR_ID, ACTOR_EMAIL)
                        .contextWrite(TenantTestContext.put())
                        .block(Duration.ofSeconds(10)));
    }

    private TenantMorbidityBasis add(String tenantId, String line, String basisName, String multiplier) {
        return service.add(uuid(tenantId),
                        new AddTenantMorbidityBasisRequest(
                                line, basisName, new BigDecimal(multiplier), null, null),
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

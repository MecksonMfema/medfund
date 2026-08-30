package com.medfund.tenancy.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.shared.testfixtures.AbstractIntegrationTest;
import com.medfund.shared.testfixtures.TenantTestContext;
import com.medfund.shared.testfixtures.WithTenant;
import com.medfund.tenancy.TenancyServiceApplication;
import com.medfund.tenancy.dto.AddTenantExpenseAssumptionRequest;
import com.medfund.tenancy.dto.UpdateTenantExpenseAssumptionRequest;
import com.medfund.tenancy.entity.TenantExpenseAssumption;
import com.medfund.tenancy.service.TenantExpenseAssumptionService;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Service-level integration tests for the Phase 15 §2 IFRS 17 expense
 * assumption CRUD surface. Covers list/add/update/delete + audit event
 * emission + cross-tenant guard + line/type validation.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        classes = TenancyServiceApplication.class)
@TestPropertySource(properties = {
        "spring.flyway.locations=classpath:db/test-migration",
        "spring.flyway.out-of-order=false"
})
@Import(TenantExpenseAssumptionIT.SecurityStub.class)
@WithTenant("00000000-0000-4000-8000-000000000001")
class TenantExpenseAssumptionIT extends AbstractIntegrationTest {

    private static final String ENTITY_TYPE = "TENANT_EXPENSE_ASSUMPTION";
    private static final String AUDIT_TOPIC = "medfund.audit.events";
    private static final String ACTOR_ID = "10000000-0000-4000-8000-000000000001";
    private static final String ACTOR_EMAIL = "admin@medfund.example";

    public static final String TENANT_ID = "00000000-0000-4000-8000-000000000001";
    public static final String OTHER_TENANT = "00000000-0000-4000-8000-000000000099";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private TenantExpenseAssumptionService service;

    @Autowired
    private R2dbcEntityTemplate r2dbcTemplate;

    @BeforeEach
    void wipeRows() {
        r2dbcTemplate.getDatabaseClient()
                .sql("DELETE FROM tenant_expense_assumption")
                .then()
                .block(Duration.ofSeconds(10));
    }

    @Test
    void add_insertsAndAuditsCreate() {
        TenantExpenseAssumption saved = add("HEALTH", "MAINTENANCE", "45.00", "USD");

        assertNotNull(saved.getId());
        assertEquals("HEALTH", saved.getInsuranceLine());
        assertEquals("MAINTENANCE", saved.getExpenseType());
        assertEquals(0, new BigDecimal("45.00").compareTo(saved.getAmountPerPolicy()));
        assertEquals("USD", saved.getCurrency());

        JsonNode event = auditEventFor("CREATE", saved.getId(), Duration.ofSeconds(10));
        assertEquals(ENTITY_TYPE, event.get("entityType").asText());
        assertTrue(event.get("entityName").asText()
                        .startsWith("ExpenseAssumption for tenant it HEALTH/MAINTENANCE USD"),
                "entityName should name tenant slug + line + type + currency: "
                        + event.get("entityName").asText());
        assertEquals(TENANT_ID, event.get("tenantId").asText());
        assertEquals(ACTOR_EMAIL, event.get("actorEmail").asText());
    }

    @Test
    void update_mutatesAmountAndAuditsUpdate() {
        TenantExpenseAssumption saved = add("LIFE", "ACQUISITION", "150.00", "USD");

        TenantExpenseAssumption updated = service.update(
                        uuid(TENANT_ID), saved.getId(),
                        new UpdateTenantExpenseAssumptionRequest(
                                new BigDecimal("175.00"),
                                "revised after 2026 review",
                                LocalDate.of(2027, 6, 30)),
                        ACTOR_ID, ACTOR_EMAIL)
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(10));

        assertNotNull(updated);
        assertEquals(0, new BigDecimal("175.00").compareTo(updated.getAmountPerPolicy()));
        assertEquals("revised after 2026 review", updated.getSourceNote());
        assertEquals(LocalDate.of(2027, 6, 30), updated.getEffectiveTo());
        // Immutable key preserved
        assertEquals("LIFE", updated.getInsuranceLine());
        assertEquals("ACQUISITION", updated.getExpenseType());
        assertEquals("USD", updated.getCurrency());

        JsonNode event = auditEventFor("UPDATE", updated.getId(), Duration.ofSeconds(10));
        List<String> changed = new ArrayList<>();
        for (JsonNode field : event.get("changedFields")) {
            changed.add(field.asText());
        }
        assertTrue(changed.contains("amountPerPolicy"),
                "changedFields should name amountPerPolicy: " + changed);
        assertTrue(changed.contains("sourceNote"),
                "changedFields should name sourceNote: " + changed);
        assertTrue(changed.contains("effectiveTo"),
                "changedFields should name effectiveTo: " + changed);
    }

    @Test
    void delete_removesAndAuditsDelete() {
        TenantExpenseAssumption saved = add("FUNERAL", "OVERHEAD", "10.00", "USD");

        service.delete(uuid(TENANT_ID), saved.getId(), ACTOR_ID, ACTOR_EMAIL)
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(10));

        JsonNode event = auditEventFor("DELETE", saved.getId(), Duration.ofSeconds(10));
        assertEquals("FUNERAL", event.get("oldValue").get("insuranceLine").asText());
        JsonNode newValue = event.get("newValue");
        assertTrue(newValue == null || newValue.isNull(), "newValue should be null on DELETE");
    }

    @Test
    void update_rejectsCrossTenantRow() {
        TenantExpenseAssumption saved = add("HEALTH", "MAINTENANCE", "45.00", "USD");

        assertThrows(IllegalArgumentException.class, () ->
                service.update(uuid(OTHER_TENANT), saved.getId(),
                                new UpdateTenantExpenseAssumptionRequest(
                                        new BigDecimal("60.00"), null, null),
                                ACTOR_ID, ACTOR_EMAIL)
                        .contextWrite(TenantTestContext.put())
                        .block(Duration.ofSeconds(10)));
    }

    @Test
    void delete_rejectsCrossTenantRow() {
        TenantExpenseAssumption saved = add("HEALTH", "MAINTENANCE", "45.00", "USD");

        assertThrows(IllegalArgumentException.class, () ->
                service.delete(uuid(OTHER_TENANT), saved.getId(), ACTOR_ID, ACTOR_EMAIL)
                        .contextWrite(TenantTestContext.put())
                        .block(Duration.ofSeconds(10)));
    }

    @Test
    void add_rejectsUnknownLine() {
        assertThrows(IllegalArgumentException.class, () ->
                service.add(uuid(TENANT_ID),
                                new AddTenantExpenseAssumptionRequest(
                                        "MADE_UP_LINE", "MAINTENANCE",
                                        new BigDecimal("10"), "USD",
                                        null, null, null),
                                ACTOR_ID, ACTOR_EMAIL)
                        .contextWrite(TenantTestContext.put())
                        .block(Duration.ofSeconds(10)));
    }

    @Test
    void add_rejectsUnknownExpenseType() {
        assertThrows(IllegalArgumentException.class, () ->
                service.add(uuid(TENANT_ID),
                                new AddTenantExpenseAssumptionRequest(
                                        "HEALTH", "MYSTERY_TYPE",
                                        new BigDecimal("10"), "USD",
                                        null, null, null),
                                ACTOR_ID, ACTOR_EMAIL)
                        .contextWrite(TenantTestContext.put())
                        .block(Duration.ofSeconds(10)));
    }

    private TenantExpenseAssumption add(String line, String type, String amount, String currency) {
        return service.add(uuid(TENANT_ID),
                        new AddTenantExpenseAssumptionRequest(
                                line, type, new BigDecimal(amount), currency,
                                "test", null, null),
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

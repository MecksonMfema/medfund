package com.medfund.tenancy.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.shared.testfixtures.AbstractIntegrationTest;
import com.medfund.shared.testfixtures.TenantTestContext;
import com.medfund.shared.testfixtures.WithTenant;
import com.medfund.tenancy.TenancyServiceApplication;
import com.medfund.tenancy.dto.AddTenantYieldCurveRequest;
import com.medfund.tenancy.dto.UpdateTenantYieldCurveRequest;
import com.medfund.tenancy.entity.TenantYieldCurve;
import com.medfund.tenancy.service.TenantYieldCurveService;
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
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Service-level integration tests for the Phase 15 §2 IFRS 17 yield curve
 * CRUD surface. Covers list/add/update/delete + audit + cross-tenant guard
 * + source-value validation + the bulk-insert helper used by CSV upload.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        classes = TenancyServiceApplication.class)
@TestPropertySource(properties = {
        "spring.flyway.locations=classpath:db/test-migration",
        "spring.flyway.out-of-order=false"
})
@Import(TenantYieldCurveIT.SecurityStub.class)
@WithTenant("00000000-0000-4000-8000-000000000001")
class TenantYieldCurveIT extends AbstractIntegrationTest {

    private static final String ENTITY_TYPE = "TENANT_YIELD_CURVE";
    private static final String AUDIT_TOPIC = "medfund.audit.events";
    private static final String ACTOR_ID = "10000000-0000-4000-8000-000000000001";
    private static final String ACTOR_EMAIL = "admin@medfund.example";

    public static final String TENANT_ID = "00000000-0000-4000-8000-000000000001";
    public static final String OTHER_TENANT = "00000000-0000-4000-8000-000000000099";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private TenantYieldCurveService service;

    @Autowired
    private R2dbcEntityTemplate r2dbcTemplate;

    @BeforeEach
    void wipeRows() {
        r2dbcTemplate.getDatabaseClient()
                .sql("DELETE FROM tenant_yield_curve")
                .then()
                .block(Duration.ofSeconds(10));
    }

    @Test
    void add_insertsAndAuditsCreate() {
        TenantYieldCurve saved = addPoint("USD", 12, "0.0450", "ADMIN");

        assertNotNull(saved.getId());
        assertEquals("USD", saved.getCurrency());
        assertEquals(12, saved.getTenorMonths());
        assertEquals(0, new BigDecimal("0.0450000").compareTo(saved.getSpotRate()));
        assertEquals("ADMIN", saved.getSource());

        JsonNode event = auditEventFor("CREATE", saved.getId(), Duration.ofSeconds(10));
        assertEquals(ENTITY_TYPE, event.get("entityType").asText());
        assertTrue(event.get("entityName").asText().startsWith("YieldCurve for tenant it USD@12m"),
                "entityName should name tenant slug + currency + tenor: "
                        + event.get("entityName").asText());
        assertEquals(TENANT_ID, event.get("tenantId").asText());
        assertEquals(ACTOR_EMAIL, event.get("actorEmail").asText());
        assertEquals("USD", event.get("newValue").get("currency").asText());
    }

    @Test
    void add_defaultsSourceToAdmin() {
        TenantYieldCurve saved = addPoint("USD", 6, "0.0400", null);
        assertEquals("ADMIN", saved.getSource());
    }

    @Test
    void addAll_bulkInsertsAllRows() {
        List<AddTenantYieldCurveRequest> rows = List.of(
                new AddTenantYieldCurveRequest("USD", 3, new BigDecimal("0.0400"),
                        "ADMIN", null, null),
                new AddTenantYieldCurveRequest("USD", 6, new BigDecimal("0.0425"),
                        "ADMIN", null, null),
                new AddTenantYieldCurveRequest("USD", 12, new BigDecimal("0.0450"),
                        "ADMIN", null, null));

        List<TenantYieldCurve> saved = service.addAll(uuid(TENANT_ID), rows, ACTOR_ID, ACTOR_EMAIL)
                .collectList()
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(20));

        assertNotNull(saved);
        assertEquals(3, saved.size());

        List<TenantYieldCurve> stored = service.listForCurrency(uuid(TENANT_ID), "USD")
                .collectList()
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(10));
        assertEquals(3, stored.size());
    }

    @Test
    void update_mutatesSpotRateAndEffectiveTo() {
        TenantYieldCurve saved = addPoint("USD", 12, "0.0450", "ADMIN");

        TenantYieldCurve updated = service.update(
                        uuid(TENANT_ID), saved.getId(),
                        new UpdateTenantYieldCurveRequest(
                                new BigDecimal("0.0475"),
                                LocalDate.of(2027, 6, 30)),
                        ACTOR_ID, ACTOR_EMAIL)
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(10));

        assertNotNull(updated);
        assertEquals(0, new BigDecimal("0.0475000").compareTo(updated.getSpotRate()));
        assertEquals(LocalDate.of(2027, 6, 30), updated.getEffectiveTo());
        // Immutable key preserved
        assertEquals("USD", updated.getCurrency());
        assertEquals(12, updated.getTenorMonths());

        auditEventFor("UPDATE", updated.getId(), Duration.ofSeconds(10));
    }

    @Test
    void delete_removesAndAuditsDelete() {
        TenantYieldCurve saved = addPoint("USD", 24, "0.0500", "ADMIN");

        service.delete(uuid(TENANT_ID), saved.getId(), ACTOR_ID, ACTOR_EMAIL)
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(10));

        JsonNode event = auditEventFor("DELETE", saved.getId(), Duration.ofSeconds(10));
        assertEquals("USD", event.get("oldValue").get("currency").asText());
        JsonNode newValue = event.get("newValue");
        assertTrue(newValue == null || newValue.isNull(), "newValue should be null on DELETE");
    }

    @Test
    void update_rejectsCrossTenantRow() {
        TenantYieldCurve saved = addPoint("USD", 12, "0.0450", "ADMIN");

        assertThrows(IllegalArgumentException.class, () ->
                service.update(uuid(OTHER_TENANT), saved.getId(),
                                new UpdateTenantYieldCurveRequest(
                                        new BigDecimal("0.0500"), null),
                                ACTOR_ID, ACTOR_EMAIL)
                        .contextWrite(TenantTestContext.put())
                        .block(Duration.ofSeconds(10)));
    }

    @Test
    void delete_rejectsCrossTenantRow() {
        TenantYieldCurve saved = addPoint("USD", 12, "0.0450", "ADMIN");

        assertThrows(IllegalArgumentException.class, () ->
                service.delete(uuid(OTHER_TENANT), saved.getId(), ACTOR_ID, ACTOR_EMAIL)
                        .contextWrite(TenantTestContext.put())
                        .block(Duration.ofSeconds(10)));
    }

    @Test
    void add_rejectsUnknownSource() {
        assertThrows(IllegalArgumentException.class, () ->
                service.add(uuid(TENANT_ID),
                                new AddTenantYieldCurveRequest(
                                        "USD", 12, new BigDecimal("0.0450"),
                                        "MYSTERY_SOURCE", null, null),
                                ACTOR_ID, ACTOR_EMAIL)
                        .contextWrite(TenantTestContext.put())
                        .block(Duration.ofSeconds(10)));
    }

    private TenantYieldCurve addPoint(String currency, int tenor, String rate, String source) {
        return service.add(uuid(TENANT_ID),
                        new AddTenantYieldCurveRequest(
                                currency, tenor, new BigDecimal(rate),
                                source, null, null),
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

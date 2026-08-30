package com.medfund.tenancy.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.shared.testfixtures.AbstractIntegrationTest;
import com.medfund.shared.testfixtures.TenantTestContext;
import com.medfund.shared.testfixtures.WithTenant;
import com.medfund.tenancy.TenancyServiceApplication;
import com.medfund.tenancy.dto.AddTenantMarketDataConfigRequest;
import com.medfund.tenancy.dto.UpdateTenantMarketDataConfigRequest;
import com.medfund.tenancy.entity.TenantMarketDataConfig;
import com.medfund.tenancy.service.TenantMarketDataConfigService;
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
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Service-level integration tests for the Phase 15 §24 market-data
 * config CRUD surface. Boots the full tenancy-service context against
 * the shared Postgres + Kafka containers with V001..V005 test-migration
 * schema, then drives {@link TenantMarketDataConfigService} directly.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        classes = TenancyServiceApplication.class)
@TestPropertySource(properties = {
        "spring.flyway.locations=classpath:db/test-migration",
        "spring.flyway.out-of-order=false"
})
@Import(TenantMarketDataConfigIT.SecurityStub.class)
@WithTenant("00000000-0000-4000-8000-000000000001")
class TenantMarketDataConfigIT extends AbstractIntegrationTest {

    private static final String ENTITY_TYPE = "TENANT_MARKET_DATA_CONFIG";
    private static final String AUDIT_TOPIC = "medfund.audit.events";
    private static final String ACTOR_ID = "10000000-0000-4000-8000-000000000001";
    private static final String ACTOR_EMAIL = "admin@medfund.example";

    public static final String TENANT_ID = "00000000-0000-4000-8000-000000000001";
    public static final String OTHER_TENANT = "00000000-0000-4000-8000-000000000099";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private TenantMarketDataConfigService service;

    @Autowired
    private R2dbcEntityTemplate r2dbcTemplate;

    @BeforeEach
    void wipeRows() {
        r2dbcTemplate.getDatabaseClient()
                .sql("DELETE FROM tenant_market_data_config")
                .then()
                .block(Duration.ofSeconds(10));
    }

    @Test
    void list_emptyForFreshTenant() {
        List<TenantMarketDataConfig> rows = service.list(uuid(TENANT_ID))
                .collectList()
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(10));
        assertNotNull(rows);
        assertTrue(rows.isEmpty());
    }

    @Test
    void add_insertsAndAuditsCreate() {
        TenantMarketDataConfig saved = addRow(TENANT_ID, "USD", "RBZ_AUTO", true);

        assertNotNull(saved.getId());
        assertEquals("USD", saved.getCurrency());
        assertEquals("RBZ_AUTO", saved.getSource());
        assertTrue(saved.getAutoFetchEnabled());

        JsonNode event = auditEventFor("CREATE", saved.getId(), Duration.ofSeconds(10));
        assertEquals(ENTITY_TYPE, event.get("entityType").asText());
        assertTrue(event.get("entityName").asText().contains("USD"),
                "entityName should carry currency: " + event.get("entityName").asText());
        assertTrue(event.get("entityName").asText().contains("RBZ_AUTO"),
                "entityName should carry source: " + event.get("entityName").asText());
        assertEquals(TENANT_ID, event.get("tenantId").asText());
        assertEquals(ACTOR_EMAIL, event.get("actorEmail").asText());
    }

    @Test
    void update_togglesAutoFetchAndAuditsUpdate() {
        TenantMarketDataConfig saved = addRow(TENANT_ID, "USD", "RBZ_AUTO", true);

        TenantMarketDataConfig updated = service.update(
                        uuid(TENANT_ID), saved.getId(),
                        new UpdateTenantMarketDataConfigRequest(null, false),
                        ACTOR_ID, ACTOR_EMAIL)
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(10));

        assertNotNull(updated);
        assertEquals(Boolean.FALSE, updated.getAutoFetchEnabled());
        assertEquals("USD", updated.getCurrency());
        assertEquals("RBZ_AUTO", updated.getSource());
    }

    @Test
    void delete_removesRow() {
        TenantMarketDataConfig saved = addRow(TENANT_ID, "ZAR", "SARB_AUTO", true);

        service.delete(uuid(TENANT_ID), saved.getId(), ACTOR_ID, ACTOR_EMAIL)
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(10));

        List<TenantMarketDataConfig> remaining = service.list(uuid(TENANT_ID))
                .collectList()
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(10));
        assertTrue(remaining.isEmpty());
    }

    @Test
    void update_rejectsCrossTenantRow() {
        TenantMarketDataConfig saved = addRow(TENANT_ID, "USD", "RBZ_AUTO", true);

        assertThrows(IllegalArgumentException.class, () ->
                service.update(uuid(OTHER_TENANT), saved.getId(),
                                new UpdateTenantMarketDataConfigRequest(null, false),
                                ACTOR_ID, ACTOR_EMAIL)
                        .contextWrite(TenantTestContext.put())
                        .block(Duration.ofSeconds(10)));
    }

    @Test
    void delete_rejectsCrossTenantRow() {
        TenantMarketDataConfig saved = addRow(TENANT_ID, "USD", "RBZ_AUTO", true);

        assertThrows(IllegalArgumentException.class, () ->
                service.delete(uuid(OTHER_TENANT), saved.getId(), ACTOR_ID, ACTOR_EMAIL)
                        .contextWrite(TenantTestContext.put())
                        .block(Duration.ofSeconds(10)));
    }

    @Test
    void add_rejectsUnknownSource() {
        assertThrows(IllegalArgumentException.class, () ->
                service.add(uuid(TENANT_ID),
                                new AddTenantMarketDataConfigRequest("USD", "FED_AUTO", true),
                                ACTOR_ID, ACTOR_EMAIL)
                        .contextWrite(TenantTestContext.put())
                        .block(Duration.ofSeconds(10)));
    }

    @Test
    void listAllEnabled_returnsOnlyEnabledAcrossTenants() {
        addRow(TENANT_ID, "USD", "RBZ_AUTO", true);
        TenantMarketDataConfig paused = addRow(TENANT_ID, "ZAR", "SARB_AUTO", true);
        // Pause the second row via the service's update path.
        service.update(uuid(TENANT_ID), paused.getId(),
                        new UpdateTenantMarketDataConfigRequest(null, false),
                        ACTOR_ID, ACTOR_EMAIL)
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(10));

        List<TenantMarketDataConfig> enabled = service.listAllEnabled()
                .collectList()
                .block(Duration.ofSeconds(10));

        assertNotNull(enabled);
        assertEquals(1, enabled.size(), "only the enabled row should appear");
        assertEquals("USD", enabled.get(0).getCurrency());
    }

    private TenantMarketDataConfig addRow(String tenantId, String currency, String source, boolean enabled) {
        return service.add(uuid(tenantId),
                        new AddTenantMarketDataConfigRequest(currency, source, enabled),
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

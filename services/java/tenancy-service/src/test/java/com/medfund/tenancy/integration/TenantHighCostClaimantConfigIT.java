package com.medfund.tenancy.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.shared.testfixtures.AbstractIntegrationTest;
import com.medfund.shared.testfixtures.TenantTestContext;
import com.medfund.shared.testfixtures.WithTenant;
import com.medfund.tenancy.TenancyServiceApplication;
import com.medfund.tenancy.dto.TenantHighCostClaimantConfigResponse;
import com.medfund.tenancy.dto.UpdateTenantHighCostClaimantConfigRequest;
import com.medfund.tenancy.service.TenantHighCostClaimantConfigService;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Service-level integration tests for the V132 high-cost-claimant threshold
 * config surface (plan §16 TenantHighCostClaimantConfigIT). Boots the full
 * tenancy-service context against the shared Postgres + Kafka containers
 * with the {@code db/test-migration} schema, then drives
 * {@link TenantHighCostClaimantConfigService} directly:
 *
 * <ul>
 *   <li>absent config reads back as "unconfigured" (nulls — G46 gap, never
 *       a fabricated default)</li>
 *   <li>upsert inserts and emits a CREATE audit event naming the tenant
 *       slug (feedback_audit_entity_name)</li>
 *   <li>re-upsert updates in place and emits an UPDATE audit event with the
 *       changed fields</li>
 *   <li>config is isolated per tenant</li>
 * </ul>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        // TenantWebFilterIT declares a nested @SpringBootConfiguration in this
        // same package; without an explicit app class, context auto-discovery
        // would boot that slice (no @ComponentScan) instead of the full app.
        classes = TenancyServiceApplication.class)
@TestPropertySource(properties = {
        "spring.flyway.locations=classpath:db/test-migration",
        "spring.flyway.out-of-order=false"
})
@Import(TenantHighCostClaimantConfigIT.SecurityStub.class)
@WithTenant("00000000-0000-4000-8000-000000000001")
class TenantHighCostClaimantConfigIT extends AbstractIntegrationTest {

    private static final String ENTITY_TYPE = "TENANT_HIGH_COST_CLAIMANT_CONFIG";
    private static final String AUDIT_TOPIC = "medfund.audit.events";
    private static final String ACTOR_ID = "10000000-0000-4000-8000-000000000001";
    private static final String ACTOR_EMAIL = "admin@medfund.example";

    public static final String TENANT_ID = "00000000-0000-4000-8000-000000000001";
    public static final String OTHER_TENANT = "00000000-0000-4000-8000-000000000099";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private TenantHighCostClaimantConfigService service;

    @Autowired
    private R2dbcEntityTemplate r2dbcTemplate;

    @BeforeEach
    void resetConfigRows() {
        // Methods share the container DB; wipe config rows so each method's
        // first upsert is a CREATE (a leftover row would turn it into a no-op
        // UPDATE whose empty changedFields audit event the earliest-reading
        // consumer would swallow first).
        r2dbcTemplate.getDatabaseClient()
                .sql("DELETE FROM tenant_high_cost_claimant_config")
                .then()
                .block(Duration.ofSeconds(10));
    }

    @Test
    void get_absentConfig_returnsUnconfigured() {
        TenantHighCostClaimantConfigResponse response = service.get(uuid(TENANT_ID))
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(10));

        assertEquals(TENANT_ID, response.tenantId().toString());
        assertNull(response.thresholdAmount());
        assertNull(response.currencyCode());
    }

    @Test
    void upsert_createsConfigAndAuditsCreate() {
        TenantHighCostClaimantConfigResponse response = upsert(TENANT_ID, "3000.0000", "ZMW");

        assertEquals(TENANT_ID, response.tenantId().toString());
        assertEquals(0, new BigDecimal("3000.0000").compareTo(response.thresholdAmount()));
        assertEquals("ZMW", response.currencyCode());
        assertTrue(response.updatedAt() != null, "created row should carry updated_at");

        JsonNode event = auditEventFor("CREATE", Duration.ofSeconds(10));
        assertEquals(ENTITY_TYPE, event.get("entityType").asText());
        assertEquals("HighCostClaimantConfig for tenant it", event.get("entityName").asText());
        assertEquals(TENANT_ID, event.get("tenantId").asText());
        assertEquals(ACTOR_EMAIL, event.get("actorEmail").asText());
        assertEquals(0, new BigDecimal("3000.0000")
                .compareTo(event.get("newValue").get("thresholdAmount").decimalValue()));
    }

    @Test
    void upsert_updatesExistingAndAuditsUpdate() {
        upsert(TENANT_ID, "3000.0000", "ZMW");
        TenantHighCostClaimantConfigResponse updated = upsert(TENANT_ID, "5000.0000", "USD");

        assertEquals(0, new BigDecimal("5000.0000").compareTo(updated.thresholdAmount()));
        assertEquals("USD", updated.currencyCode());

        JsonNode event = auditEventFor("UPDATE", Duration.ofSeconds(10));
        assertEquals(ENTITY_TYPE, event.get("entityType").asText());
        assertEquals("HighCostClaimantConfig for tenant it", event.get("entityName").asText());
        List<String> changed = new ArrayList<>();
        for (JsonNode field : event.get("changedFields")) {
            changed.add(field.asText());
        }
        assertTrue(changed.contains("thresholdAmount"), "changedFields should name thresholdAmount: " + changed);
        assertTrue(changed.contains("currencyCode"), "changedFields should name currencyCode: " + changed);
        assertEquals(0, new BigDecimal("3000.0000")
                .compareTo(event.get("oldValue").get("thresholdAmount").decimalValue()));
        assertEquals(0, new BigDecimal("5000.0000")
                .compareTo(event.get("newValue").get("thresholdAmount").decimalValue()));
    }

    @Test
    void get_returnsConfiguredThreshold() {
        upsert(TENANT_ID, "3000.0000", "ZMW");

        TenantHighCostClaimantConfigResponse response = service.get(uuid(TENANT_ID))
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(10));

        assertEquals(0, new BigDecimal("3000.0000").compareTo(response.thresholdAmount()));
        assertEquals("ZMW", response.currencyCode());
    }

    @Test
    void upsert_isIsolatedPerTenant() {
        upsert(TENANT_ID, "3000.0000", "ZMW");

        TenantHighCostClaimantConfigResponse other = service.get(uuid(OTHER_TENANT))
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(10));
        assertNull(other.thresholdAmount(), "other tenant should still be unconfigured");
    }

    private TenantHighCostClaimantConfigResponse upsert(String tenantId, String threshold, String currency) {
        return service.upsert(uuid(tenantId),
                        new UpdateTenantHighCostClaimantConfigRequest(
                                new BigDecimal(threshold), currency),
                        ACTOR_ID, ACTOR_EMAIL)
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(10));
    }

    private JsonNode auditEventFor(String action, Duration timeout) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "audit-assert-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");

        long deadline = System.currentTimeMillis() + timeout.toMillis();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(AUDIT_TOPIC));
            while (System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> rec : records) {
                    try {
                        JsonNode node = MAPPER.readTree(rec.value());
                        if (ENTITY_TYPE.equals(node.path("entityType").asText())
                                && action.equals(node.path("action").asText())) {
                            return node;
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        throw new AssertionError("no " + ENTITY_TYPE + "/" + action + " audit event within " + timeout);
    }

    private static UUID uuid(String s) {
        return UUID.fromString(s);
    }

    /** Stubbed JWT decoder so the tenancy SecurityConfig wires cleanly. */
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

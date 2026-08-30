package com.medfund.tenancy.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.shared.testfixtures.AbstractIntegrationTest;
import com.medfund.shared.testfixtures.TenantTestContext;
import com.medfund.shared.testfixtures.WithTenant;
import com.medfund.tenancy.TenancyServiceApplication;
import com.medfund.tenancy.dto.AddTenantRaConfigRequest;
import com.medfund.tenancy.dto.UpdateTenantRaConfigRequest;
import com.medfund.tenancy.entity.TenantRaConfig;
import com.medfund.tenancy.service.TenantRaConfigService;
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
 * Service-level integration tests for the Phase 15 §2 IFRS 17 RA config
 * CRUD surface. Boots the full tenancy-service context against the shared
 * Postgres + Kafka containers with the {@code db/test-migration} schema
 * (V001..V003), then drives {@link TenantRaConfigService} directly:
 *
 * <ul>
 *   <li>list empty for a fresh tenant</li>
 *   <li>add(COC) inserts + emits a CREATE audit event whose
 *       {@code entityName} names tenant slug + portfolio + methodology
 *       (feedback_audit_entity_name)</li>
 *   <li>add(CI) inserts + emits a CREATE event; each methodology accepts
 *       only its matching numeric parameter</li>
 *   <li>update mutates only the matching numeric parameter + source note +
 *       effective_to, emits UPDATE with the changed fields</li>
 *   <li>delete removes the row + emits a DELETE event</li>
 *   <li>Rule-2: update/delete for another tenant reject with
 *       IllegalArgumentException</li>
 *   <li>add(COC) with no cocRate rejects; add(CI) with no
 *       targetConfidenceLevel rejects; add(COC) with both set rejects</li>
 * </ul>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        classes = TenancyServiceApplication.class)
@TestPropertySource(properties = {
        "spring.flyway.locations=classpath:db/test-migration",
        "spring.flyway.out-of-order=false"
})
@Import(TenantRaConfigIT.SecurityStub.class)
@WithTenant("00000000-0000-4000-8000-000000000001")
class TenantRaConfigIT extends AbstractIntegrationTest {

    private static final String ENTITY_TYPE = "TENANT_RA_CONFIG";
    private static final String AUDIT_TOPIC = "medfund.audit.events";
    private static final String ACTOR_ID = "10000000-0000-4000-8000-000000000001";
    private static final String ACTOR_EMAIL = "admin@medfund.example";

    public static final String TENANT_ID = "00000000-0000-4000-8000-000000000001";
    public static final String OTHER_TENANT = "00000000-0000-4000-8000-000000000099";

    private static final String PORTFOLIO_A = "20000000-0000-4000-8000-00000000000a";
    private static final String PORTFOLIO_B = "20000000-0000-4000-8000-00000000000b";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private TenantRaConfigService service;

    @Autowired
    private R2dbcEntityTemplate r2dbcTemplate;

    @BeforeEach
    void wipeRows() {
        r2dbcTemplate.getDatabaseClient()
                .sql("DELETE FROM tenant_ra_config")
                .then()
                .block(Duration.ofSeconds(10));
    }

    @Test
    void list_emptyForFreshTenant() {
        List<TenantRaConfig> rows = service.list(uuid(TENANT_ID))
                .collectList()
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(10));
        assertNotNull(rows);
        assertTrue(rows.isEmpty(), "no rows expected after wipe");
    }

    @Test
    void addCoc_insertsAndAuditsCreate() {
        TenantRaConfig saved = addCoc(TENANT_ID, PORTFOLIO_A, "0.06");

        assertNotNull(saved.getId());
        assertEquals("COC", saved.getMethodology());
        assertEquals(0, new BigDecimal("0.0600").compareTo(saved.getCocRate()));

        JsonNode event = auditEventFor("CREATE", saved.getId(), Duration.ofSeconds(10));
        assertEquals(ENTITY_TYPE, event.get("entityType").asText());
        assertTrue(event.get("entityName").asText().startsWith("RaConfig for tenant it portfolio "),
                "entityName should name tenant slug + portfolio + methodology: "
                        + event.get("entityName").asText());
        assertTrue(event.get("entityName").asText().contains("(COC)"),
                "entityName should carry the methodology: " + event.get("entityName").asText());
        assertEquals(TENANT_ID, event.get("tenantId").asText());
        assertEquals(ACTOR_EMAIL, event.get("actorEmail").asText());
        assertEquals("COC", event.get("newValue").get("methodology").asText());
    }

    @Test
    void addCi_insertsAndAuditsCreate() {
        TenantRaConfig saved = addCi(TENANT_ID, PORTFOLIO_B, "0.75");

        assertEquals("CI", saved.getMethodology());
        assertEquals(0, new BigDecimal("0.7500").compareTo(saved.getTargetConfidenceLevel()));

        JsonNode event = auditEventFor("CREATE", saved.getId(), Duration.ofSeconds(10));
        assertEquals("CI", event.get("newValue").get("methodology").asText());
    }

    @Test
    void update_mutatesParamAndAuditsUpdate() {
        TenantRaConfig saved = addCoc(TENANT_ID, PORTFOLIO_A, "0.06");

        TenantRaConfig updated = service.update(
                        uuid(TENANT_ID), saved.getId(),
                        new UpdateTenantRaConfigRequest(
                                new BigDecimal("0.0700"), null,
                                "revised after 2026 review",
                                LocalDate.of(2027, 12, 31)),
                        ACTOR_ID, ACTOR_EMAIL)
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(10));

        assertNotNull(updated);
        assertEquals(0, new BigDecimal("0.0700").compareTo(updated.getCocRate()));
        assertEquals("revised after 2026 review", updated.getSourceNote());
        assertEquals(LocalDate.of(2027, 12, 31), updated.getEffectiveTo());
        // Methodology + key preserved
        assertEquals("COC", updated.getMethodology());
        assertEquals(uuid(PORTFOLIO_A), updated.getPortfolioId());

        JsonNode event = auditEventFor("UPDATE", updated.getId(), Duration.ofSeconds(10));
        List<String> changed = new ArrayList<>();
        for (JsonNode field : event.get("changedFields")) {
            changed.add(field.asText());
        }
        assertTrue(changed.contains("cocRate"), "changedFields should name cocRate: " + changed);
        assertTrue(changed.contains("sourceNote"), "changedFields should name sourceNote: " + changed);
        assertTrue(changed.contains("effectiveTo"), "changedFields should name effectiveTo: " + changed);
    }

    @Test
    void delete_removesAndAuditsDelete() {
        TenantRaConfig saved = addCi(TENANT_ID, PORTFOLIO_A, "0.85");

        service.delete(uuid(TENANT_ID), saved.getId(), ACTOR_ID, ACTOR_EMAIL)
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(10));

        List<TenantRaConfig> remaining = service.list(uuid(TENANT_ID))
                .collectList()
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(10));
        assertTrue(remaining.isEmpty(), "row should be gone after delete");

        JsonNode event = auditEventFor("DELETE", saved.getId(), Duration.ofSeconds(10));
        assertEquals("CI", event.get("oldValue").get("methodology").asText());
        JsonNode newValue = event.get("newValue");
        assertTrue(newValue == null || newValue.isNull(), "newValue should be null on DELETE");
    }

    @Test
    void update_rejectsCrossTenantRow() {
        TenantRaConfig saved = addCoc(TENANT_ID, PORTFOLIO_A, "0.06");

        assertThrows(IllegalArgumentException.class, () ->
                service.update(uuid(OTHER_TENANT), saved.getId(),
                                new UpdateTenantRaConfigRequest(
                                        new BigDecimal("0.0800"), null, null, null),
                                ACTOR_ID, ACTOR_EMAIL)
                        .contextWrite(TenantTestContext.put())
                        .block(Duration.ofSeconds(10)));
    }

    @Test
    void delete_rejectsCrossTenantRow() {
        TenantRaConfig saved = addCoc(TENANT_ID, PORTFOLIO_A, "0.06");

        assertThrows(IllegalArgumentException.class, () ->
                service.delete(uuid(OTHER_TENANT), saved.getId(), ACTOR_ID, ACTOR_EMAIL)
                        .contextWrite(TenantTestContext.put())
                        .block(Duration.ofSeconds(10)));
    }

    @Test
    void addCoc_withoutCocRate_rejects() {
        assertThrows(IllegalArgumentException.class, () ->
                service.add(uuid(TENANT_ID),
                                new AddTenantRaConfigRequest(
                                        uuid(PORTFOLIO_A), "COC", null, null,
                                        null, null, null),
                                ACTOR_ID, ACTOR_EMAIL)
                        .contextWrite(TenantTestContext.put())
                        .block(Duration.ofSeconds(10)));
    }

    @Test
    void addCi_withoutTargetConfidenceLevel_rejects() {
        assertThrows(IllegalArgumentException.class, () ->
                service.add(uuid(TENANT_ID),
                                new AddTenantRaConfigRequest(
                                        uuid(PORTFOLIO_A), "CI", null, null,
                                        null, null, null),
                                ACTOR_ID, ACTOR_EMAIL)
                        .contextWrite(TenantTestContext.put())
                        .block(Duration.ofSeconds(10)));
    }

    @Test
    void addCoc_withBothParams_rejects() {
        assertThrows(IllegalArgumentException.class, () ->
                service.add(uuid(TENANT_ID),
                                new AddTenantRaConfigRequest(
                                        uuid(PORTFOLIO_A), "COC",
                                        new BigDecimal("0.0600"),
                                        new BigDecimal("0.7500"),
                                        null, null, null),
                                ACTOR_ID, ACTOR_EMAIL)
                        .contextWrite(TenantTestContext.put())
                        .block(Duration.ofSeconds(10)));
    }

    @Test
    void add_rejectsUnknownMethodology() {
        assertThrows(IllegalArgumentException.class, () ->
                service.add(uuid(TENANT_ID),
                                new AddTenantRaConfigRequest(
                                        uuid(PORTFOLIO_A), "MYSTERY",
                                        new BigDecimal("0.06"), null,
                                        null, null, null),
                                ACTOR_ID, ACTOR_EMAIL)
                        .contextWrite(TenantTestContext.put())
                        .block(Duration.ofSeconds(10)));
    }

    private TenantRaConfig addCoc(String tenantId, String portfolioId, String cocRate) {
        return service.add(uuid(tenantId),
                        new AddTenantRaConfigRequest(
                                uuid(portfolioId), "COC",
                                new BigDecimal(cocRate), null,
                                "test", null, null),
                        ACTOR_ID, ACTOR_EMAIL)
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(10));
    }

    private TenantRaConfig addCi(String tenantId, String portfolioId, String ci) {
        return service.add(uuid(tenantId),
                        new AddTenantRaConfigRequest(
                                uuid(portfolioId), "CI",
                                null, new BigDecimal(ci),
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

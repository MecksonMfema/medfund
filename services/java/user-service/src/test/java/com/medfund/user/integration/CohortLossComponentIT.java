package com.medfund.user.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.shared.testfixtures.AbstractIntegrationTest;
import com.medfund.shared.testfixtures.TenantTestContext;
import com.medfund.shared.testfixtures.WithTenant;
import com.medfund.user.service.CohortLossComponentService;
import com.medfund.user.service.KeycloakSyncService;
import com.medfund.user.service.UserEventPublisher;
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
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 15 §5 (I19) IT — end-to-end through {@link CohortLossComponentService}:
 * <ul>
 *   <li>Each movement type inserts a row + fires an {@code AuditEvent} with
 *       friendly {@code entityName} ({@code "<movement> <amount> <currency>"}).</li>
 *   <li>{@code refreshMatview()} rebuilds {@code cohort_loss_component_current}
 *       so subsequent {@code openingBalance()} reads reflect the new movements.</li>
 *   <li>Signs work out: INITIAL_RECOGNITION adds; RELEASE / REVERSAL /
 *       RECLASSIFICATION_TO_NON_ONEROUS subtract.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
    "spring.flyway.locations=classpath:db/cohort-loss-component-migration",
    "spring.flyway.baseline-on-migrate=true",
    // baseline-version=0 keeps V001 in the applied set even when Flyway auto-baselines
    // an already-populated schema (which happens when a peer IT booted first and
    // created some of the shared tables against a different history table). Combined
    // with the distinct flyway.table below and CREATE TABLE IF NOT EXISTS in the
    // baseline, this makes the IT order-independent.
    "spring.flyway.baseline-version=0",
    "spring.flyway.table=flyway_history_loss_component",
})
@Import(CohortLossComponentIT.SecurityStub.class)
class CohortLossComponentIT extends AbstractIntegrationTest {

    @TestConfiguration
    static class SecurityStub {
        @Bean
        ReactiveJwtDecoder reactiveJwtDecoder() {
            return token -> Mono.just(new Jwt(
                token, Instant.now(), Instant.now().plusSeconds(300),
                Map.of("alg", "none"),
                Map.of("sub", "test", "iss", "test")
            ));
        }
    }

    static final String TENANT_ID = "00000000-0000-4000-8000-000000000042";
    static final String AUDIT_TOPIC = "medfund.audit.events";

    @Autowired private DatabaseClient db;
    @Autowired private CohortLossComponentService service;

    @MockBean private UserEventPublisher userEventPublisher;
    @MockBean private KeycloakSyncService keycloakSyncService;

    private UUID cohortId;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Shared JVM Kafka aggregates events across tests — filter by our specific
     * entityId so this poll doesn't pick up other tests' rows.
     */
    private JsonNode pollAuditForEntityId(String entityId, Duration timeout) {
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
                        if (entityId.equals(node.path("entityId").asText())) {
                            return node;
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        return null;
    }

    @BeforeEach
    void seed() {
        db.sql("DELETE FROM cohort_loss_component_history").then().block(Duration.ofSeconds(10));
        db.sql("DELETE FROM ifrs17_cohort").then().block(Duration.ofSeconds(10));
        db.sql("DELETE FROM ifrs17_portfolio").then().block(Duration.ofSeconds(10));

        UUID portfolioId = UUID.randomUUID();
        db.sql("INSERT INTO ifrs17_portfolio (id, name, insurance_line) VALUES (:id, 'HEALTH-CORE', 'HEALTH')")
                .bind("id", portfolioId).then().block(Duration.ofSeconds(10));

        cohortId = UUID.randomUUID();
        db.sql("""
                INSERT INTO ifrs17_cohort (id, portfolio_id, cohort_year, cohort_type, name)
                VALUES (:id, :pid, 2026, 'ONEROUS', 'HEALTH-2026-ONEROUS')
                """)
                .bind("id", cohortId).bind("pid", portfolioId)
                .then().block(Duration.ofSeconds(10));

        // Ensure matview is empty at test start.
        db.sql("REFRESH MATERIALIZED VIEW cohort_loss_component_current")
                .then().block(Duration.ofSeconds(10));
    }

    @Test
    @WithTenant(TENANT_ID)
    void recordMovement_initialRecognition_insertsRowAndEmitsAuditWithFriendlyName() {
        UUID actorId = UUID.randomUUID();

        var saved = service.recordMovement(cohortId, "INITIAL_RECOGNITION",
                        new BigDecimal("50000.00"), "USD", null, actorId,
                        "alice@example.com", "budget review")
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(15));

        assertThat(saved).isNotNull();
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getMovementType()).isEqualTo("INITIAL_RECOGNITION");
        assertThat(saved.getAmount()).isEqualByComparingTo("50000.00");
        assertThat(saved.getCurrency()).isEqualTo("USD");

        Long count = db.sql("SELECT COUNT(*)::bigint AS n FROM cohort_loss_component_history WHERE cohort_id = :id")
                .bind("id", cohortId)
                .map((r, meta) -> r.get("n", Long.class))
                .one().block(Duration.ofSeconds(10));
        assertThat(count).isEqualTo(1L);

        // entityName = friendly text per feedback_audit_entity_name (never the UUID).
        JsonNode audit = pollAuditForEntityId(saved.getId().toString(), Duration.ofSeconds(15));
        assertThat(audit).as("expected audit event on " + AUDIT_TOPIC).isNotNull();
        assertThat(audit.path("entityType").asText()).isEqualTo("CohortLossComponentHistory");
        assertThat(audit.path("entityName").asText()).isEqualTo("INITIAL_RECOGNITION 50000.00 USD");
        assertThat(audit.path("action").asText()).isEqualTo("CREATE");
        assertThat(audit.path("actorEmail").asText()).isEqualTo("alice@example.com");
    }

    @Test
    @WithTenant(TENANT_ID)
    void openingBalance_computesSignedSumViaMatviewRefresh() {
        // Two INITIAL_RECOGNITION rows and one RELEASE — matview refresh must
        // add the two and subtract the release.
        service.recordMovement(cohortId, "INITIAL_RECOGNITION",
                        new BigDecimal("50000.00"), "USD", null, UUID.randomUUID(),
                        "alice@example.com", null)
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(15));
        service.recordMovement(cohortId, "INITIAL_RECOGNITION",
                        new BigDecimal("10000.00"), "USD", null, UUID.randomUUID(),
                        "alice@example.com", null)
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(15));
        service.recordMovement(cohortId, "RELEASE",
                        new BigDecimal("15000.00"), "USD", null, UUID.randomUUID(),
                        "alice@example.com", null)
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(15));

        // Before refresh the matview is empty — openingBalance returns 0.
        BigDecimal beforeRefresh = service.openingBalance(cohortId, "USD")
                .block(Duration.ofSeconds(10));
        assertThat(beforeRefresh).isEqualByComparingTo("0");

        // Refresh + verify.
        service.refreshMatview().block(Duration.ofSeconds(15));
        BigDecimal afterRefresh = service.openingBalance(cohortId, "USD")
                .block(Duration.ofSeconds(10));
        assertThat(afterRefresh).isEqualByComparingTo("45000.00"); // 50k + 10k - 15k
    }

    @Test
    @WithTenant(TENANT_ID)
    void openingBalance_unknownCurrency_returnsZero() {
        service.recordMovement(cohortId, "INITIAL_RECOGNITION",
                        new BigDecimal("50000.00"), "USD", null, UUID.randomUUID(),
                        "alice@example.com", null)
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(15));
        service.refreshMatview().block(Duration.ofSeconds(15));

        BigDecimal zwlBalance = service.openingBalance(cohortId, "ZWL")
                .block(Duration.ofSeconds(10));
        assertThat(zwlBalance).isEqualByComparingTo("0");
    }

    @Test
    @WithTenant(TENANT_ID)
    void reclassificationToNonOnerous_subtractsFromBalance() {
        service.recordMovement(cohortId, "INITIAL_RECOGNITION",
                        new BigDecimal("30000.00"), "ZWL", null, UUID.randomUUID(),
                        "alice@example.com", null)
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(15));
        service.recordMovement(cohortId, "RECLASSIFICATION_TO_NON_ONEROUS",
                        new BigDecimal("30000.00"), "ZWL", null, UUID.randomUUID(),
                        "alice@example.com", "recovered per new mortality basis")
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(15));

        service.refreshMatview().block(Duration.ofSeconds(15));

        BigDecimal balance = service.openingBalance(cohortId, "ZWL")
                .block(Duration.ofSeconds(10));
        assertThat(balance).isEqualByComparingTo("0");
    }

    @Test
    @WithTenant(TENANT_ID)
    void findByCohortId_returnsMovementsInDescendingEffectiveAtOrder() {
        service.recordMovement(cohortId, "INITIAL_RECOGNITION",
                        new BigDecimal("10000.00"), "USD", null, UUID.randomUUID(),
                        "alice@example.com", "initial")
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(15));
        service.recordMovement(cohortId, "RELEASE",
                        new BigDecimal("2000.00"), "USD", null, UUID.randomUUID(),
                        "alice@example.com", "release")
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(15));

        var rows = service.findByCohortId(cohortId)
                .collectList().block(Duration.ofSeconds(15));

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getMovementType()).isEqualTo("RELEASE");
        assertThat(rows.get(1).getMovementType()).isEqualTo("INITIAL_RECOGNITION");
    }
}

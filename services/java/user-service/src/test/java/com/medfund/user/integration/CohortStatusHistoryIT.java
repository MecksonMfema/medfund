package com.medfund.user.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.shared.testfixtures.AbstractIntegrationTest;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.util.List;
import java.util.Properties;
import com.medfund.shared.testfixtures.TenantTestContext;
import com.medfund.shared.testfixtures.WithTenant;
import com.medfund.user.service.CohortStatusHistoryService;
import com.medfund.user.service.Ifrs17MaterialEventPublisher;
import com.medfund.user.service.KeycloakSyncService;
import com.medfund.user.service.UserEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 15 §4 (I11) IT — end-to-end through {@link CohortStatusHistoryService}:
 * <ul>
 *   <li>MANUAL transition inserts a row + emits an {@code AuditEvent} to Kafka,
 *       does NOT invoke the material-event publisher.</li>
 *   <li>AUTO transition to ONEROUS invokes the material-event publisher with
 *       {@code WARN} severity — the placeholder implementation swapped for a
 *       stub bean here so we can assert invocation without spinning up the
 *       Phase 19 Kafka producer.</li>
 *   <li>{@link CohortStatusHistoryService#findByCohortId} returns rows in
 *       descending {@code effective_at} order.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
    "spring.flyway.locations=classpath:db/cohort-status-history-migration",
    "spring.flyway.baseline-on-migrate=true",
    // baseline-version=0 + a distinct history table make the IT order-independent
    // when the JVM-scoped Postgres testcontainer is shared with Phase 5's
    // CohortLossComponentIT. Without these, whichever IT boots second sees a
    // pre-populated schema, auto-baselines to V001, and never runs its own baseline.
    "spring.flyway.baseline-version=0",
    "spring.flyway.table=flyway_history_status",
})
@Import({CohortStatusHistoryIT.SecurityStub.class, CohortStatusHistoryIT.MaterialEventStub.class})
class CohortStatusHistoryIT extends AbstractIntegrationTest {

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

    /**
     * Phase 4 stand-in for {@link Ifrs17MaterialEventPublisher}. Uses a MockBean
     * so we can verify invocation without the Phase 19 Kafka producer.
     */
    @TestConfiguration
    static class MaterialEventStub {
        @Bean
        @org.springframework.context.annotation.Primary
        Ifrs17MaterialEventPublisher materialEventPublisher() {
            return org.mockito.Mockito.mock(Ifrs17MaterialEventPublisher.class);
        }
    }

    static final String TENANT_ID = "00000000-0000-4000-8000-000000000042";
    static final String AUDIT_TOPIC = "medfund.audit.events";

    @Autowired private DatabaseClient db;
    @Autowired private CohortStatusHistoryService service;
    @Autowired private Ifrs17MaterialEventPublisher materialEventPublisher;

    @MockBean private UserEventPublisher userEventPublisher;
    @MockBean private KeycloakSyncService keycloakSyncService;

    private UUID cohortId;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Kafka is shared across every IT in the JVM, so filtering by entityType alone
     * matches any {@code CohortStatusHistory} event ever emitted — including the
     * other tests in this class. Fresh consumer group + earliest-reset ensures
     * we replay the whole topic; the predicate narrows to our specific row.
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
        // Reset per-test — DELETE not TRUNCATE because ON DELETE CASCADE handles history rows.
        db.sql("DELETE FROM cohort_status_history").then().block(Duration.ofSeconds(10));
        db.sql("DELETE FROM ifrs17_cohort").then().block(Duration.ofSeconds(10));
        db.sql("DELETE FROM ifrs17_portfolio").then().block(Duration.ofSeconds(10));

        UUID portfolioId = UUID.randomUUID();
        db.sql("INSERT INTO ifrs17_portfolio (id, name, insurance_line) VALUES (:id, 'HEALTH-CORE', 'HEALTH')")
                .bind("id", portfolioId).then().block(Duration.ofSeconds(10));

        cohortId = UUID.randomUUID();
        db.sql("""
                INSERT INTO ifrs17_cohort (id, portfolio_id, cohort_year, cohort_type, name)
                VALUES (:id, :pid, 2026, 'NON_ONEROUS', 'HEALTH-2026-NON_ONEROUS')
                """)
                .bind("id", cohortId).bind("pid", portfolioId)
                .then().block(Duration.ofSeconds(10));

        // Material event stub returns success — but we assert invocation via captor.
        lenient().when(materialEventPublisher.publish(any(), any(), any(), any(), any()))
                .thenReturn(Mono.empty());
    }

    @Test
    @WithTenant(TENANT_ID)
    void manualTransition_insertsRow_emitsAuditEvent_noMaterialEvent() {
        UUID actorId = UUID.randomUUID();

        var saved = service.recordTransition(cohortId, "NON_ONEROUS", "ONEROUS",
                        "MANUAL_OVERRIDE", "MANUAL", null, actorId,
                        "alice@example.com", "budget review")
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(15));

        assertThat(saved).isNotNull();
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getFromStatus()).isEqualTo("NON_ONEROUS");
        assertThat(saved.getToStatus()).isEqualTo("ONEROUS");
        assertThat(saved.getTransitionSource()).isEqualTo("MANUAL");

        // Row landed.
        Long count = db.sql("SELECT COUNT(*)::bigint AS n FROM cohort_status_history WHERE cohort_id = :id")
                .bind("id", cohortId)
                .map((r, meta) -> r.get("n", Long.class))
                .one().block(Duration.ofSeconds(10));
        assertThat(count).isEqualTo(1L);

        // AuditEvent fired on medfund.audit.events with the entityName = "from → to" (friendly),
        // NOT the UUID (per feedback_audit_entity_name). Filter by our specific entityId
        // because the shared JVM Kafka container aggregates events across tests.
        JsonNode audit = pollAuditForEntityId(saved.getId().toString(), Duration.ofSeconds(15));
        assertThat(audit).as("expected audit event on " + AUDIT_TOPIC).isNotNull();
        assertThat(audit.path("entityType").asText()).isEqualTo("CohortStatusHistory");
        assertThat(audit.path("entityName").asText()).isEqualTo("NON_ONEROUS → ONEROUS");
        assertThat(audit.path("action").asText()).isEqualTo("CREATE");
        assertThat(audit.path("actorEmail").asText()).isEqualTo("alice@example.com");

        // No material event on MANUAL — that path is reserved for AUTO onerous transitions.
        verify(materialEventPublisher, org.mockito.Mockito.never())
                .publish(any(), any(), any(), any(), any());
    }

    @Test
    @WithTenant(TENANT_ID)
    void autoTransitionToOnerous_alsoEmitsMaterialEventWithWarnSeverity() {
        UUID sourceRunId = UUID.randomUUID();

        service.recordTransition(cohortId, "NON_ONEROUS", "ONEROUS",
                        "AUTO_TEST_FAILED", "AUTO", sourceRunId, null,
                        "system@medfund", "FCF exceeded remaining CSM")
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(15));

        ArgumentCaptor<String> severityCaptor = ArgumentCaptor.forClass(String.class);
        verify(materialEventPublisher, times(1)).publish(
                org.mockito.ArgumentMatchers.eq(cohortId),
                org.mockito.ArgumentMatchers.eq("ONEROUS_TRANSITION"),
                severityCaptor.capture(),
                org.mockito.ArgumentMatchers.any(String.class),
                org.mockito.ArgumentMatchers.eq(sourceRunId));
        assertThat(severityCaptor.getValue()).isEqualTo("WARN");
    }

    @Test
    @WithTenant(TENANT_ID)
    void findByCohortId_returnsRowsInDescendingEffectiveAtOrder() {
        UUID actorId = UUID.randomUUID();

        service.recordTransition(cohortId, "NON_ONEROUS", "UNCERTAIN",
                        "MANUAL_OVERRIDE", "MANUAL", null, actorId,
                        "alice@example.com", "review needed")
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(15));

        service.recordTransition(cohortId, "UNCERTAIN", "ONEROUS",
                        "MANUAL_OVERRIDE", "MANUAL", null, actorId,
                        "alice@example.com", "confirmed onerous")
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(15));

        var rows = service.findByCohortId(cohortId)
                .collectList().block(Duration.ofSeconds(15));

        assertThat(rows).hasSize(2);
        // Newest first — the ONEROUS row lands after UNCERTAIN.
        assertThat(rows.get(0).getToStatus()).isEqualTo("ONEROUS");
        assertThat(rows.get(1).getToStatus()).isEqualTo("UNCERTAIN");
    }
}

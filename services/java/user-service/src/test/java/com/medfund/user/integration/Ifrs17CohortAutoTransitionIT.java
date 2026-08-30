package com.medfund.user.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.shared.testfixtures.AbstractIntegrationTest;
import com.medfund.shared.testfixtures.TenantTestContext;
import com.medfund.shared.testfixtures.WithTenant;
import com.medfund.user.dto.AutoTransitionRequest;
import com.medfund.user.service.CohortStatusHistoryService;
import com.medfund.user.service.Ifrs17MaterialEventPublisher;
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
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 15 §15 (I11) IT — end-to-end through
 * {@link CohortStatusHistoryService#recordAutoTransition}: the ai-service
 * onerous-test callback path.
 *
 * <p>Covers the four cases the plan calls out:
 * <ol>
 *   <li>Fresh onerous transition writes {@code cohort_status_history} +
 *       {@code cohort_loss_component_history} + emits both AuditEvents
 *       (one per row) + emits {@code medfund.ifrs17.material-event} with
 *       {@code ONEROUS_TRANSITION}/{@code WARN}.</li>
 *   <li>Fresh onerous transition also flips {@code ifrs17_cohort.cohort_type}
 *       to the target status.</li>
 *   <li>Idempotency on {@code (cohortId, sourceRunId)}: duplicate call
 *       returns the original status-history row with no additional writes
 *       or material events.</li>
 *   <li>Recovery path (transitionReason=AUTO_TEST_RECOVERED) with null
 *       lossComponentAmount writes only the status-history row.</li>
 * </ol>
 *
 * <p>The material-event publisher is stubbed via {@link MaterialEventStub}
 * — the real Kafka producer lands in §19 alongside the
 * {@code tenant_ifrs17_notification_config} admin surface.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
    "spring.flyway.locations=classpath:db/cohort-auto-transition-migration",
    "spring.flyway.baseline-on-migrate=true",
    // baseline-version=0 + distinct history table = order-independent when the
    // shared JVM Postgres container already has a partial shape from a peer IT.
    "spring.flyway.baseline-version=0",
    "spring.flyway.table=flyway_history_auto_transition",
})
@Import({Ifrs17CohortAutoTransitionIT.SecurityStub.class, Ifrs17CohortAutoTransitionIT.MaterialEventStub.class})
class Ifrs17CohortAutoTransitionIT extends AbstractIntegrationTest {

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

    @TestConfiguration
    static class MaterialEventStub {
        @Bean
        @Primary
        Ifrs17MaterialEventPublisher materialEventPublisher() {
            return mock(Ifrs17MaterialEventPublisher.class);
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

    @BeforeEach
    void seed() {
        db.sql("DELETE FROM cohort_loss_component_history").then().block(Duration.ofSeconds(10));
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

        // Reset the mock between tests — MockBean lifecycle would carry invocations across.
        org.mockito.Mockito.reset(materialEventPublisher);
        lenient().when(materialEventPublisher.publish(any(), any(), any(), any(), any()))
                .thenReturn(Mono.empty());
    }

    /**
     * Shared JVM Kafka aggregates events across tests — filter by entityId
     * so this poll doesn't pick up other tests' rows.
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

    private String cohortTypeInDb() {
        return db.sql("SELECT cohort_type FROM ifrs17_cohort WHERE id = :id")
                .bind("id", cohortId)
                .map((r, meta) -> r.get("cohort_type", String.class))
                .one().block(Duration.ofSeconds(10));
    }

    private Long countStatusHistoryRows() {
        return db.sql("SELECT COUNT(*)::bigint AS n FROM cohort_status_history WHERE cohort_id = :id")
                .bind("id", cohortId)
                .map((r, meta) -> r.get("n", Long.class))
                .one().block(Duration.ofSeconds(10));
    }

    private Long countLossComponentRows() {
        return db.sql("SELECT COUNT(*)::bigint AS n FROM cohort_loss_component_history WHERE cohort_id = :id")
                .bind("id", cohortId)
                .map((r, meta) -> r.get("n", Long.class))
                .one().block(Duration.ofSeconds(10));
    }

    // ── happy path — fresh onerous transition ──────────────────────────

    @Test
    @WithTenant(TENANT_ID)
    void autoTransitionFailed_writesStatusHistory_lossComponent_bothAudits_materialEvent_flipsCohort() {
        UUID sourceRunId = UUID.randomUUID();
        var request = new AutoTransitionRequest(
                "NON_ONEROUS",
                "ONEROUS",
                "AUTO_TEST_FAILED",
                sourceRunId,
                new BigDecimal("20.00"),
                "USD",
                "FCF exceeded remaining CSM"
        );

        var statusRow = service.recordAutoTransition(cohortId, request)
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(20));

        assertThat(statusRow).isNotNull();
        assertThat(statusRow.getFromStatus()).isEqualTo("NON_ONEROUS");
        assertThat(statusRow.getToStatus()).isEqualTo("ONEROUS");
        assertThat(statusRow.getTransitionSource()).isEqualTo("AUTO");
        assertThat(statusRow.getSourceRunId()).isEqualTo(sourceRunId);

        // Cohort's cohort_type flipped.
        assertThat(cohortTypeInDb()).isEqualTo("ONEROUS");

        // Both rows landed.
        assertThat(countStatusHistoryRows()).isEqualTo(1L);
        assertThat(countLossComponentRows()).isEqualTo(1L);

        // Loss component row carries the amount + INITIAL_RECOGNITION + source_run_id linkage.
        BigDecimal lcAmount = db.sql("SELECT amount FROM cohort_loss_component_history WHERE cohort_id = :id")
                .bind("id", cohortId)
                .map((r, meta) -> r.get("amount", BigDecimal.class))
                .one().block(Duration.ofSeconds(10));
        assertThat(lcAmount).isEqualByComparingTo("20.00");

        String lcMovement = db.sql("SELECT movement_type FROM cohort_loss_component_history WHERE cohort_id = :id")
                .bind("id", cohortId)
                .map((r, meta) -> r.get("movement_type", String.class))
                .one().block(Duration.ofSeconds(10));
        assertThat(lcMovement).isEqualTo("INITIAL_RECOGNITION");

        UUID lcSourceRun = db.sql("SELECT source_run_id FROM cohort_loss_component_history WHERE cohort_id = :id")
                .bind("id", cohortId)
                .map((r, meta) -> r.get("source_run_id", UUID.class))
                .one().block(Duration.ofSeconds(10));
        assertThat(lcSourceRun).isEqualTo(sourceRunId);

        // Both audit events fire — one per row (via the existing recordTransition +
        // recordMovement publishers).
        JsonNode statusAudit = pollAuditForEntityId(statusRow.getId().toString(), Duration.ofSeconds(15));
        assertThat(statusAudit).as("status-history audit event on " + AUDIT_TOPIC).isNotNull();
        assertThat(statusAudit.path("entityType").asText()).isEqualTo("CohortStatusHistory");
        assertThat(statusAudit.path("entityName").asText()).isEqualTo("NON_ONEROUS → ONEROUS");
        assertThat(statusAudit.path("action").asText()).isEqualTo("CREATE");
        assertThat(statusAudit.path("actorEmail").asText()).isEqualTo("system@medfund");

        UUID lcId = db.sql("SELECT id FROM cohort_loss_component_history WHERE cohort_id = :id")
                .bind("id", cohortId)
                .map((r, meta) -> r.get("id", UUID.class))
                .one().block(Duration.ofSeconds(10));
        JsonNode lcAudit = pollAuditForEntityId(lcId.toString(), Duration.ofSeconds(15));
        assertThat(lcAudit).as("loss-component audit event on " + AUDIT_TOPIC).isNotNull();
        assertThat(lcAudit.path("entityType").asText()).isEqualTo("CohortLossComponentHistory");
        assertThat(lcAudit.path("entityName").asText()).isEqualTo("INITIAL_RECOGNITION 20.00 USD");

        // Material event fires with WARN severity + sourceRunId propagated.
        verify(materialEventPublisher, times(1)).publish(
                eq(cohortId), eq("ONEROUS_TRANSITION"), eq("WARN"),
                any(String.class), eq(sourceRunId));
    }

    // ── idempotency ────────────────────────────────────────────────────

    @Test
    @WithTenant(TENANT_ID)
    void duplicateCall_sameSourceRunId_returnsOriginalRowNoNewWrites() {
        UUID sourceRunId = UUID.randomUUID();
        var request = new AutoTransitionRequest(
                "NON_ONEROUS",
                "ONEROUS",
                "AUTO_TEST_FAILED",
                sourceRunId,
                new BigDecimal("100.00"),
                "USD",
                null
        );

        var first = service.recordAutoTransition(cohortId, request)
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(20));

        assertThat(first).isNotNull();
        assertThat(countStatusHistoryRows()).isEqualTo(1L);
        assertThat(countLossComponentRows()).isEqualTo(1L);
        verify(materialEventPublisher, times(1)).publish(
                any(), any(), any(), any(), any());

        // Second call with same sourceRunId returns the SAME status-history row
        // and does not write again or fire another material event.
        var second = service.recordAutoTransition(cohortId, request)
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(20));

        assertThat(second).isNotNull();
        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(countStatusHistoryRows()).isEqualTo(1L);
        assertThat(countLossComponentRows()).isEqualTo(1L);
        // Still exactly one material event — no second publish on the idempotent path.
        verify(materialEventPublisher, times(1)).publish(
                any(), any(), any(), any(), any());
    }

    // ── recovery — no loss component amount ────────────────────────────

    @Test
    @WithTenant(TENANT_ID)
    void autoTransitionRecovered_writesOnlyStatusHistory_noLossComponent_infoMaterialEvent() {
        // Seed cohort as already ONEROUS.
        db.sql("UPDATE ifrs17_cohort SET cohort_type = 'ONEROUS' WHERE id = :id")
                .bind("id", cohortId).then().block(Duration.ofSeconds(10));

        UUID sourceRunId = UUID.randomUUID();
        var request = new AutoTransitionRequest(
                "ONEROUS",
                "NON_ONEROUS",
                "AUTO_TEST_RECOVERED",
                sourceRunId,
                null,           // no loss component amount on recovery
                null,
                "FCF fell back within CSM"
        );

        service.recordAutoTransition(cohortId, request)
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(20));

        assertThat(cohortTypeInDb()).isEqualTo("NON_ONEROUS");
        assertThat(countStatusHistoryRows()).isEqualTo(1L);
        // No loss component row on recovery — matview handles the reclassification.
        assertThat(countLossComponentRows()).isEqualTo(0L);

        // Recovery emits INFO severity (to_status != ONEROUS per CohortStatusHistoryService).
        verify(materialEventPublisher, times(1)).publish(
                eq(cohortId), eq("ONEROUS_TRANSITION"), eq("INFO"),
                any(String.class), eq(sourceRunId));
    }

    // ── stale snapshot guard ───────────────────────────────────────────

    @Test
    @WithTenant(TENANT_ID)
    void fromStatusMismatch_rejects409_noWrites() {
        // Cohort is currently NON_ONEROUS but request claims fromStatus=ONEROUS.
        var request = new AutoTransitionRequest(
                "ONEROUS",             // wrong — cohort is NON_ONEROUS
                "NON_ONEROUS",
                "AUTO_TEST_RECOVERED",
                UUID.randomUUID(),
                null,
                null,
                null
        );

        StepVerifier.create(service.recordAutoTransition(cohortId, request)
                        .contextWrite(TenantTestContext.put()))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(((ResponseStatusException) err).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                })
                .verify(Duration.ofSeconds(15));

        // Cohort untouched; no rows written; no material event fired.
        assertThat(cohortTypeInDb()).isEqualTo("NON_ONEROUS");
        assertThat(countStatusHistoryRows()).isEqualTo(0L);
        assertThat(countLossComponentRows()).isEqualTo(0L);
        verify(materialEventPublisher, never()).publish(any(), any(), any(), any(), any());
    }

    // ── currency guard ─────────────────────────────────────────────────

    @Test
    @WithTenant(TENANT_ID)
    void lossComponentAmountWithoutCurrency_rejects400_noWrites() {
        var request = new AutoTransitionRequest(
                "NON_ONEROUS",
                "ONEROUS",
                "AUTO_TEST_FAILED",
                UUID.randomUUID(),
                new BigDecimal("50.00"),
                null,          // missing currency — application-level guard hoisted BEFORE any DB write
                null
        );

        StepVerifier.create(service.recordAutoTransition(cohortId, request)
                        .contextWrite(TenantTestContext.put()))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(((ResponseStatusException) err).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                })
                .verify(Duration.ofSeconds(15));

        // Guard runs before any DB write — nothing landed anywhere.
        assertThat(cohortTypeInDb()).isEqualTo("NON_ONEROUS");
        assertThat(countStatusHistoryRows()).isEqualTo(0L);
        assertThat(countLossComponentRows()).isEqualTo(0L);
        verify(materialEventPublisher, never()).publish(any(), any(), any(), any(), any());
    }

    // ── cohort-not-found ───────────────────────────────────────────────

    @Test
    @WithTenant(TENANT_ID)
    void unknownCohortId_errorsNotFound() {
        UUID unknown = UUID.randomUUID();
        var request = new AutoTransitionRequest(
                "NON_ONEROUS",
                "ONEROUS",
                "AUTO_TEST_FAILED",
                UUID.randomUUID(),
                new BigDecimal("10.00"),
                "USD",
                null
        );

        StepVerifier.create(service.recordAutoTransition(unknown, request)
                        .contextWrite(TenantTestContext.put()))
                .expectError(com.medfund.user.exception.Ifrs17CohortNotFoundException.class)
                .verify(Duration.ofSeconds(15));
    }
}

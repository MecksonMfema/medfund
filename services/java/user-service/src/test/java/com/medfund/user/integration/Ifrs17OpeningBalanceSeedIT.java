package com.medfund.user.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.shared.testfixtures.AbstractIntegrationTest;
import com.medfund.shared.testfixtures.TenantTestContext;
import com.medfund.shared.testfixtures.WithTenant;
import com.medfund.user.dto.CreateIfrs17OpeningBalanceSeedRequest;
import com.medfund.user.dto.UpdateIfrs17OpeningBalanceSeedRequest;
import com.medfund.user.entity.Ifrs17OpeningBalanceSeed;
import com.medfund.user.exception.Ifrs17CohortNotFoundException;
import com.medfund.user.exception.Ifrs17OpeningBalanceSeedNotFoundException;
import com.medfund.user.exception.Ifrs17PortfolioNotFoundException;
import com.medfund.user.service.Ifrs17OpeningBalanceSeedService;
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
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 15 §7 (I29) IT — end-to-end through
 * {@link Ifrs17OpeningBalanceSeedService}:
 * <ul>
 *   <li>{@code create} inserts the row + emits an {@code AuditEvent} with
 *       friendly {@code entityName}
 *       ({@code "<balance_type> <amount> <currency> @ <effective_from>"})
 *       and the operator's actor identity carried through from the
 *       controller boundary.</li>
 *   <li>Duplicate {@code (portfolio, cohort, currency, balance_type,
 *       effective_from)} rejects with 409 CONFLICT.</li>
 *   <li>Cohort belonging to a different portfolio rejects with 400.</li>
 *   <li>Missing portfolio / cohort raise the well-known 404s.</li>
 *   <li>{@code update} mutates only amount + reasonNote and fires a
 *       CREATE-with-DIFF audit; {@code delete} fires a DELETE audit and
 *       drops the row.</li>
 *   <li>{@code findLatestFor} consulted by §17 shaping returns the seed
 *       row on-or-before the report period start; empty when nothing
 *       matches.</li>
 * </ul>
 *
 * <p>Test-migration folder + distinct {@code flyway.table} follow the
 * Phase 4/5/6 precedent so this IT can co-exist with peer user-service
 * ITs on the shared JVM-scoped Postgres testcontainer without a
 * boot-order race.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
    "spring.flyway.locations=classpath:db/opening-balance-seed-migration",
    "spring.flyway.baseline-on-migrate=true",
    "spring.flyway.baseline-version=0",
    "spring.flyway.table=flyway_history_opening_balance_seed",
})
@Import(Ifrs17OpeningBalanceSeedIT.SecurityStub.class)
class Ifrs17OpeningBalanceSeedIT extends AbstractIntegrationTest {

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

    static final String TENANT_ID = "00000000-0000-4000-8000-0000000000b1";
    static final String AUDIT_TOPIC = "medfund.audit.events";

    @Autowired private DatabaseClient db;
    @Autowired private Ifrs17OpeningBalanceSeedService service;

    @MockBean private UserEventPublisher userEventPublisher;
    @MockBean private KeycloakSyncService keycloakSyncService;

    private UUID portfolioId;
    private UUID cohortId;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Shared JVM Kafka aggregates events across tests — filter to our entityId. */
    private JsonNode pollAuditForEntityId(String entityId, String action, Duration timeout) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "audit-opening-balance-" + UUID.randomUUID());
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
                        if (entityId.equals(node.path("entityId").asText())
                                && action.equals(node.path("action").asText())) {
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
        db.sql("DELETE FROM ifrs17_opening_balance_seed").then().block(Duration.ofSeconds(10));
        db.sql("DELETE FROM ifrs17_cohort").then().block(Duration.ofSeconds(10));
        db.sql("DELETE FROM ifrs17_portfolio").then().block(Duration.ofSeconds(10));

        portfolioId = UUID.randomUUID();
        db.sql("INSERT INTO ifrs17_portfolio (id, name, insurance_line) VALUES (:id, 'HEALTH-CORE', 'HEALTH')")
                .bind("id", portfolioId).then().block(Duration.ofSeconds(10));

        cohortId = UUID.randomUUID();
        db.sql("""
                INSERT INTO ifrs17_cohort (id, portfolio_id, cohort_year, cohort_type, name)
                VALUES (:id, :pid, 2026, 'NON_ONEROUS', 'HEALTH-2026-NON-ONEROUS')
                """)
                .bind("id", cohortId).bind("pid", portfolioId)
                .then().block(Duration.ofSeconds(10));
    }

    private CreateIfrs17OpeningBalanceSeedRequest addRequest(String balanceType, String amount,
                                                             LocalDate effectiveFrom) {
        return new CreateIfrs17OpeningBalanceSeedRequest(
                portfolioId, cohortId, "USD", balanceType,
                new BigDecimal(amount), effectiveFrom,
                "manual override for jan opening");
    }

    @Test
    @WithTenant(TENANT_ID)
    void create_insertsRow_andEmitsAuditWithFriendlyName() {
        UUID actorId = UUID.randomUUID();

        var saved = service.create(addRequest("LRC", "50000.00", LocalDate.of(2026, 1, 1)),
                        actorId.toString(), "alice@example.com")
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(15));

        assertThat(saved).isNotNull();
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getPortfolioId()).isEqualTo(portfolioId);
        assertThat(saved.getCohortId()).isEqualTo(cohortId);
        assertThat(saved.getBalanceType()).isEqualTo("LRC");
        assertThat(saved.getAmount()).isEqualByComparingTo("50000.00");
        assertThat(saved.getCurrency()).isEqualTo("USD");
        assertThat(saved.getEffectiveFrom()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(saved.getActorEmail()).isEqualTo("alice@example.com");
        assertThat(saved.getActorId()).isEqualTo(actorId);

        Long count = db.sql("SELECT COUNT(*)::bigint AS n FROM ifrs17_opening_balance_seed WHERE cohort_id = :id")
                .bind("id", cohortId)
                .map((r, meta) -> r.get("n", Long.class))
                .one().block(Duration.ofSeconds(10));
        assertThat(count).isEqualTo(1L);

        // Friendly entityName per feedback_audit_entity_name — never the UUID.
        JsonNode audit = pollAuditForEntityId(saved.getId().toString(), "CREATE", Duration.ofSeconds(15));
        assertThat(audit).as("expected CREATE audit event on " + AUDIT_TOPIC).isNotNull();
        assertThat(audit.path("entityType").asText()).isEqualTo("Ifrs17OpeningBalanceSeed");
        assertThat(audit.path("entityName").asText()).isEqualTo("LRC 50000.00 USD @ 2026-01-01");
        assertThat(audit.path("actorEmail").asText()).isEqualTo("alice@example.com");
        assertThat(audit.path("actorId").asText()).isEqualTo(actorId.toString());
    }

    @Test
    @WithTenant(TENANT_ID)
    void create_duplicateTuple_returns409() {
        var first = addRequest("LRC", "50000.00", LocalDate.of(2026, 1, 1));
        service.create(first, UUID.randomUUID().toString(), "alice@example.com")
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(15));

        StepVerifier.create(
                service.create(first, UUID.randomUUID().toString(), "bob@example.com")
                        .contextWrite(TenantTestContext.put()))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(((ResponseStatusException) err).getStatusCode().value()).isEqualTo(409);
                })
                .verify(Duration.ofSeconds(10));
    }

    @Test
    @WithTenant(TENANT_ID)
    void create_cohortNotInPortfolio_returns400() {
        UUID otherPortfolioId = UUID.randomUUID();
        db.sql("INSERT INTO ifrs17_portfolio (id, name, insurance_line) VALUES (:id, 'LIFE-CORE', 'LIFE')")
                .bind("id", otherPortfolioId).then().block(Duration.ofSeconds(10));

        var wrongPortfolio = new CreateIfrs17OpeningBalanceSeedRequest(
                otherPortfolioId, cohortId, "USD", "LRC",
                new BigDecimal("50000.00"), LocalDate.of(2026, 1, 1),
                "wrong portfolio arm");

        StepVerifier.create(
                service.create(wrongPortfolio, UUID.randomUUID().toString(), "alice@example.com")
                        .contextWrite(TenantTestContext.put()))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(((ResponseStatusException) err).getStatusCode().value()).isEqualTo(400);
                })
                .verify(Duration.ofSeconds(10));
    }

    @Test
    @WithTenant(TENANT_ID)
    void create_unknownPortfolio_returns404() {
        var req = new CreateIfrs17OpeningBalanceSeedRequest(
                UUID.randomUUID(), cohortId, "USD", "LRC",
                new BigDecimal("50000.00"), LocalDate.of(2026, 1, 1),
                "unknown portfolio");

        StepVerifier.create(
                service.create(req, UUID.randomUUID().toString(), "alice@example.com")
                        .contextWrite(TenantTestContext.put()))
                .expectError(Ifrs17PortfolioNotFoundException.class)
                .verify(Duration.ofSeconds(10));
    }

    @Test
    @WithTenant(TENANT_ID)
    void create_unknownCohort_returns404() {
        var req = new CreateIfrs17OpeningBalanceSeedRequest(
                portfolioId, UUID.randomUUID(), "USD", "LRC",
                new BigDecimal("50000.00"), LocalDate.of(2026, 1, 1),
                "unknown cohort");

        StepVerifier.create(
                service.create(req, UUID.randomUUID().toString(), "alice@example.com")
                        .contextWrite(TenantTestContext.put()))
                .expectError(Ifrs17CohortNotFoundException.class)
                .verify(Duration.ofSeconds(10));
    }

    @Test
    @WithTenant(TENANT_ID)
    void update_mutatesAmountAndReasonNote_andEmitsAudit() {
        var saved = service.create(addRequest("LIC", "12000.00", LocalDate.of(2026, 1, 1)),
                        UUID.randomUUID().toString(), "alice@example.com")
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(15));
        assertThat(saved).isNotNull();

        var updateReq = new UpdateIfrs17OpeningBalanceSeedRequest(
                new BigDecimal("15000.00"),
                "adjusted after Jan closing review");
        UUID updaterId = UUID.randomUUID();

        var updated = service.update(saved.getId(), updateReq,
                        updaterId.toString(), "bob@example.com")
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(15));

        assertThat(updated).isNotNull();
        assertThat(updated.getAmount()).isEqualByComparingTo("15000.00");
        assertThat(updated.getReasonNote()).isEqualTo("adjusted after Jan closing review");
        assertThat(updated.getActorId()).isEqualTo(updaterId);
        assertThat(updated.getActorEmail()).isEqualTo("bob@example.com");
        // Identity fields untouched.
        assertThat(updated.getPortfolioId()).isEqualTo(portfolioId);
        assertThat(updated.getCohortId()).isEqualTo(cohortId);
        assertThat(updated.getEffectiveFrom()).isEqualTo(LocalDate.of(2026, 1, 1));

        JsonNode audit = pollAuditForEntityId(saved.getId().toString(), "UPDATE", Duration.ofSeconds(15));
        assertThat(audit).as("expected UPDATE audit").isNotNull();
        assertThat(audit.path("entityName").asText()).isEqualTo("LIC 15000.00 USD @ 2026-01-01");
        assertThat(audit.path("actorEmail").asText()).isEqualTo("bob@example.com");
    }

    @Test
    @WithTenant(TENANT_ID)
    void update_unknown_returns404() {
        var updateReq = new UpdateIfrs17OpeningBalanceSeedRequest(
                new BigDecimal("15000.00"),
                "unused");
        StepVerifier.create(
                service.update(UUID.randomUUID(), updateReq,
                                UUID.randomUUID().toString(), "alice@example.com")
                        .contextWrite(TenantTestContext.put()))
                .expectError(Ifrs17OpeningBalanceSeedNotFoundException.class)
                .verify(Duration.ofSeconds(10));
    }

    @Test
    @WithTenant(TENANT_ID)
    void delete_dropsRow_andEmitsAudit() {
        var saved = service.create(addRequest("LRC", "77000.00", LocalDate.of(2026, 1, 1)),
                        UUID.randomUUID().toString(), "alice@example.com")
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(15));
        assertThat(saved).isNotNull();

        service.delete(saved.getId(), UUID.randomUUID().toString(), "charlie@example.com")
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(15));

        Long count = db.sql("SELECT COUNT(*)::bigint AS n FROM ifrs17_opening_balance_seed WHERE id = :id")
                .bind("id", saved.getId())
                .map((r, meta) -> r.get("n", Long.class))
                .one().block(Duration.ofSeconds(10));
        assertThat(count).isEqualTo(0L);

        JsonNode audit = pollAuditForEntityId(saved.getId().toString(), "DELETE", Duration.ofSeconds(15));
        assertThat(audit).as("expected DELETE audit").isNotNull();
        assertThat(audit.path("entityName").asText()).isEqualTo("LRC 77000.00 USD @ 2026-01-01");
        assertThat(audit.path("actorEmail").asText()).isEqualTo("charlie@example.com");
    }

    @Test
    @WithTenant(TENANT_ID)
    void findLatestFor_returnsMostRecentOnOrBeforeAsOf() {
        // Two seeds for the same tuple at different effective dates.
        service.create(addRequest("LRC", "50000.00", LocalDate.of(2026, 1, 1)),
                        UUID.randomUUID().toString(), "alice@example.com")
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(15));
        service.create(addRequest("LRC", "62000.00", LocalDate.of(2026, 4, 1)),
                        UUID.randomUUID().toString(), "alice@example.com")
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(15));

        Ifrs17OpeningBalanceSeed asOfMarch = service.findLatestFor(
                        portfolioId, cohortId, "USD", "LRC", LocalDate.of(2026, 3, 15))
                .block(Duration.ofSeconds(10));
        assertThat(asOfMarch).isNotNull();
        assertThat(asOfMarch.getAmount()).isEqualByComparingTo("50000.00");

        Ifrs17OpeningBalanceSeed asOfMay = service.findLatestFor(
                        portfolioId, cohortId, "USD", "LRC", LocalDate.of(2026, 5, 1))
                .block(Duration.ofSeconds(10));
        assertThat(asOfMay).isNotNull();
        assertThat(asOfMay.getAmount()).isEqualByComparingTo("62000.00");

        // Before any seed → empty.
        Ifrs17OpeningBalanceSeed pre = service.findLatestFor(
                        portfolioId, cohortId, "USD", "LRC", LocalDate.of(2025, 12, 31))
                .block(Duration.ofSeconds(10));
        assertThat(pre).isNull();
    }

    @Test
    @WithTenant(TENANT_ID)
    void findAll_returnsAllRowsOrdered() {
        service.create(addRequest("LRC", "50000.00", LocalDate.of(2026, 1, 1)),
                        UUID.randomUUID().toString(), "alice@example.com")
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(15));
        service.create(addRequest("LIC", "12000.00", LocalDate.of(2026, 1, 1)),
                        UUID.randomUUID().toString(), "alice@example.com")
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(15));

        var rows = service.findAll().collectList().block(Duration.ofSeconds(15));
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(Ifrs17OpeningBalanceSeed::getBalanceType)
                .containsExactlyInAnyOrder("LRC", "LIC");
    }
}

package com.medfund.finance.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.finance.dto.CtcPaymentDtos.CreateCtcPaymentRequest;
import com.medfund.finance.dto.CtcPaymentDtos.ReverseCtcPaymentRequest;
import com.medfund.finance.entity.CtcPayment;
import com.medfund.finance.repository.CtcPaymentRepository;
import com.medfund.finance.repository.MemberPayableApplicationRepository;
import com.medfund.finance.repository.MemberPayableRepository;
import com.medfund.finance.service.CtcPaymentService;
import com.medfund.shared.testfixtures.AbstractIntegrationTest;
import com.medfund.shared.testfixtures.TenantTestContext;
import com.medfund.shared.testfixtures.WithTenant;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.TestPropertySource;
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

/**
 * Slice-level integration test exercising the full CtcPaymentService pipeline:
 * validation → R2DBC INSERT → audit envelope emitted to Kafka. Phase 3 extends
 * the harness to cover the commit-writes-application-row + reverse-writes-
 * compensating-row flows, plus the {@code medfund.finance.ctc.committed} /
 * {@code medfund.finance.ctc.reversed} downstream events.
 *
 * <p>Every Phase-3 test seeds a real {@code member_payables} row first so the
 * new {@code memberPayableId} FK on {@code CreateCtcPaymentRequest} has a
 * valid target.
 *
 * <p>Mirrors {@code SchemeServiceIT} in contributions-service — same
 * Testcontainers Postgres + Kafka base ({@link AbstractIntegrationTest}), same
 * {@code test-migration/V001__ctc.sql} placement, same {@code SecurityStub}
 * for the {@link ReactiveJwtDecoder} bean.
 *
 * <p>Audit-event assertions filter by both {@code entityType} and
 * {@code entityId} because Kafka is {@code withReuse(true)} across tests —
 * a bare entityType filter would return the first CtcPayment event on the
 * topic (usually the CREATE from an earlier test), not the one this test
 * just produced.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
    "spring.flyway.locations=classpath:db/test-migration",
    "spring.flyway.baseline-on-migrate=true"
})
@Import(CtcPaymentServiceIT.SecurityStub.class)
class CtcPaymentServiceIT extends AbstractIntegrationTest {

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

    private static final String TENANT_ID = "00000000-0000-4000-8000-000000000001";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired private CtcPaymentService service;
    @Autowired private CtcPaymentRepository repository;
    @Autowired private MemberPayableRepository memberPayableRepository;
    @Autowired private MemberPayableApplicationRepository applicationRepository;
    @Autowired private DatabaseClient db;

    @Test
    @WithTenant(TENANT_ID)
    void create_persistsRowAndEmitsAuditEventToKafka() {
        UUID memberId = UUID.randomUUID();
        UUID payableId = seedPayable(memberId, "USD", new BigDecimal("500.00"));
        var request = new CreateCtcPaymentRequest(
            null, memberId, new BigDecimal("150.00"), "USD", null, payableId);
        String actorId    = UUID.randomUUID().toString();
        String actorEmail = "actor@test.example";

        UUID savedId = service.create(request, actorId, actorEmail)
            .contextWrite(TenantTestContext.put())
            .map(CtcPayment::getId)
            .block(Duration.ofSeconds(10));

        assertThat(savedId).isNotNull();

        StepVerifier.create(repository.findById(savedId)
                .contextWrite(TenantTestContext.put()))
            .assertNext(found -> {
                assertThat(found.getMemberId()).isEqualTo(memberId);
                assertThat(found.getMemberPayableId()).isEqualTo(payableId);
                assertThat(found.getAmount()).isEqualByComparingTo("150.00");
                assertThat(found.getCurrencyCode()).isEqualTo("USD");
                assertThat(found.getStatus()).isEqualTo("draft");
                assertThat(found.getType()).isEqualTo("CTC");
                assertThat(found.getCommitted()).isFalse();
            })
            .verifyComplete();

        JsonNode event = awaitAuditEvent(savedId, "CREATE", Duration.ofSeconds(10));
        assertThat(event).as("audit event for CtcPayment CREATE should reach Kafka").isNotNull();
        assertThat(event.path("tenantId").asText()).isEqualTo(TENANT_ID);
        assertThat(event.path("entityType").asText()).isEqualTo("CtcPayment");
        assertThat(event.path("actorId").asText()).isEqualTo(actorId);
        // Every audit event carries actorEmail — see feedback_audit_actor_email.
        assertThat(event.path("actorEmail").asText())
            .as("actorEmail must reach Kafka so audit listings are human-scannable")
            .isEqualTo(actorEmail);
        // Friendly name, not UUID — see feedback_audit_entity_name.
        assertThat(event.path("entityName").asText())
            .as("entityName is friendly text, not the UUID")
            .isEqualTo("CTC 150.00 USD");
        assertThat(event.path("newValue").path("amount").asText()).isEqualTo("150.00");
        assertThat(event.path("newValue").path("currencyCode").asText()).isEqualTo("USD");
        assertThat(event.path("newValue").path("status").asText()).isEqualTo("draft");
    }

    @Test
    @WithTenant(TENANT_ID)
    void commit_flipsStatusAndWritesApplicationRow_andEmitsCtcCommittedEvent() {
        UUID memberId = UUID.randomUUID();
        UUID payableId = seedPayable(memberId, "USD", new BigDecimal("500.00"));
        String actorId    = UUID.randomUUID().toString();
        String actorEmail = "committer@test.example";

        UUID id = service.create(
                new CreateCtcPaymentRequest(null, memberId, new BigDecimal("42.00"), "USD",
                        null, payableId),
                actorId, actorEmail)
            .contextWrite(TenantTestContext.put())
            .map(CtcPayment::getId)
            .block(Duration.ofSeconds(10));
        assertThat(id).isNotNull();

        CtcPayment committed = service.commit(id, actorId, actorEmail)
            .contextWrite(TenantTestContext.put())
            .block(Duration.ofSeconds(10));
        assertThat(committed).isNotNull();
        assertThat(committed.getStatus()).isEqualTo("committed");
        assertThat(committed.getCommittedAt()).isNotNull();

        // Application row landed with +amount, source_type='CTC'.
        StepVerifier.create(applicationRepository.findBySource("CTC", id)
                .contextWrite(TenantTestContext.put())
                .collectList())
            .assertNext(apps -> {
                assertThat(apps).hasSize(1);
                assertThat(apps.get(0).getAmountApplied()).isEqualByComparingTo("42.00");
                assertThat(apps.get(0).getMemberPayableId()).isEqualTo(payableId);
            })
            .verifyComplete();

        // UPDATE audit event fires with status transition draft → committed.
        JsonNode event = awaitAuditEvent(id, "UPDATE", Duration.ofSeconds(10));
        assertThat(event).as("commit should emit UPDATE audit event for the same entityId").isNotNull();
        assertThat(event.path("oldValue").path("status").asText()).isEqualTo("draft");
        assertThat(event.path("newValue").path("status").asText()).isEqualTo("committed");

        // Downstream event on the CTC-committed topic — contributions-service
        // consumes this to post the CTC_OFFSET transaction.
        JsonNode ctcCommitted = awaitTopicEvent("medfund.finance.ctc.committed",
                node -> id.toString().equals(node.path("ctcId").asText()),
                Duration.ofSeconds(10));
        assertThat(ctcCommitted).as("commit should publish CTC_COMMITTED downstream").isNotNull();
        assertThat(ctcCommitted.path("memberId").asText()).isEqualTo(memberId.toString());
        assertThat(ctcCommitted.path("memberPayableId").asText()).isEqualTo(payableId.toString());
        assertThat(new BigDecimal(ctcCommitted.path("amount").asText()))
                .isEqualByComparingTo("42.00");
        assertThat(ctcCommitted.path("tenantId").asText()).isEqualTo(TENANT_ID);
    }

    @Test
    @WithTenant(TENANT_ID)
    void commit_fullyConsumedPayable_flipsPayableToApplied() {
        UUID memberId = UUID.randomUUID();
        UUID payableId = seedPayable(memberId, "USD", new BigDecimal("100.00"));
        UUID id = service.create(
                new CreateCtcPaymentRequest(null, memberId, new BigDecimal("100.00"), "USD",
                        null, payableId),
                UUID.randomUUID().toString(), "seed@test.example")
            .contextWrite(TenantTestContext.put())
            .map(CtcPayment::getId)
            .block(Duration.ofSeconds(10));
        assertThat(id).isNotNull();

        service.commit(id, UUID.randomUUID().toString(), "committer@test.example")
            .contextWrite(TenantTestContext.put())
            .block(Duration.ofSeconds(10));

        // Payable is now fully consumed → status should be 'applied'.
        StepVerifier.create(memberPayableRepository.findById(payableId)
                .contextWrite(TenantTestContext.put()))
            .assertNext(mp -> assertThat(mp.getStatus()).isEqualTo("applied"))
            .verifyComplete();
    }

    @Test
    @WithTenant(TENANT_ID)
    void commit_alreadyCommitted_isIdempotentNoOp() {
        UUID memberId = UUID.randomUUID();
        UUID payableId = seedPayable(memberId, "USD", new BigDecimal("500.00"));
        UUID id = service.create(
                new CreateCtcPaymentRequest(null, memberId, new BigDecimal("7.50"), "USD",
                        null, payableId),
                UUID.randomUUID().toString(), "seed@test.example")
            .contextWrite(TenantTestContext.put())
            .map(CtcPayment::getId)
            .block(Duration.ofSeconds(10));
        assertThat(id).isNotNull();

        // First commit — flips status and emits UPDATE.
        service.commit(id, UUID.randomUUID().toString(), "first@test.example")
            .contextWrite(TenantTestContext.put())
            .block(Duration.ofSeconds(10));
        assertThat(awaitAuditEvent(id, "UPDATE", Duration.ofSeconds(10)))
            .as("first commit should produce a UPDATE audit event").isNotNull();

        // Second commit — must be a no-op. Same row returned, no new UPDATE audit event.
        CtcPayment secondCall = service.commit(id, UUID.randomUUID().toString(), "second@test.example")
            .contextWrite(TenantTestContext.put())
            .block(Duration.ofSeconds(10));
        assertThat(secondCall).isNotNull();
        assertThat(secondCall.getStatus()).isEqualTo("committed");

        // Count UPDATE events for this entityId — must be exactly 1.
        int updateCount = countAuditEvents(id, "UPDATE", Duration.ofSeconds(3));
        assertThat(updateCount)
            .as("idempotent commit must not emit a second UPDATE audit event")
            .isEqualTo(1);
    }

    @Test
    @WithTenant(TENANT_ID)
    void reverse_committed_createsCompensatingRow_reopensPayable_andEmitsCtcReversedEvent() {
        UUID memberId = UUID.randomUUID();
        UUID payableId = seedPayable(memberId, "USD", new BigDecimal("100.00"));
        UUID id = service.create(
                new CreateCtcPaymentRequest(null, memberId, new BigDecimal("100.00"), "USD",
                        null, payableId),
                UUID.randomUUID().toString(), "seed@test.example")
            .contextWrite(TenantTestContext.put())
            .map(CtcPayment::getId)
            .block(Duration.ofSeconds(10));
        service.commit(id, UUID.randomUUID().toString(), "committer@test.example")
            .contextWrite(TenantTestContext.put())
            .block(Duration.ofSeconds(10));

        CtcPayment compensating = service.reverse(id,
                new ReverseCtcPaymentRequest("clerk error"),
                UUID.randomUUID().toString(), "hod@test.example")
            .contextWrite(TenantTestContext.put())
            .block(Duration.ofSeconds(10));

        assertThat(compensating).isNotNull();
        assertThat(compensating.getType()).isEqualTo("REVERSAL");
        assertThat(compensating.getStatus()).isEqualTo("committed");
        assertThat(compensating.getReversesCtcId()).isEqualTo(id);

        // Original now marked reversed.
        StepVerifier.create(repository.findById(id).contextWrite(TenantTestContext.put()))
            .assertNext(orig -> assertThat(orig.getStatus()).isEqualTo("reversed"))
            .verifyComplete();

        // Payable reopened.
        StepVerifier.create(memberPayableRepository.findById(payableId)
                .contextWrite(TenantTestContext.put()))
            .assertNext(mp -> assertThat(mp.getStatus()).isEqualTo("open"))
            .verifyComplete();

        // Both application rows present — the +amount from commit and the
        // -amount from reverse. Net = zero.
        StepVerifier.create(applicationRepository.findByMemberPayableId(payableId)
                .contextWrite(TenantTestContext.put())
                .collectList())
            .assertNext(apps -> {
                assertThat(apps).hasSize(2);
                BigDecimal net = apps.stream()
                        .map(a -> a.getAmountApplied())
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                assertThat(net).isEqualByComparingTo("0");
            })
            .verifyComplete();

        // Downstream CTC_REVERSED event fires with the reason payload.
        UUID compensatingId = compensating.getId();
        JsonNode ctcReversed = awaitTopicEvent("medfund.finance.ctc.reversed",
                node -> id.toString().equals(node.path("originalCtcId").asText())
                     && compensatingId.toString().equals(node.path("compensatingCtcId").asText()),
                Duration.ofSeconds(10));
        assertThat(ctcReversed).as("reverse should publish CTC_REVERSED downstream").isNotNull();
        assertThat(ctcReversed.path("reason").asText()).isEqualTo("clerk error");
    }

    // ── Test helpers ──────────────────────────────────────────────────────

    private UUID seedPayable(UUID memberId, String currency, BigDecimal amount) {
        UUID payableId = UUID.randomUUID();
        db.sql("""
                INSERT INTO member_payables (id, member_id, claim_id, amount, currency_code, status)
                VALUES (:id, :memberId, :claimId, :amount, :currency, 'open')
                """)
            .bind("id", payableId)
            .bind("memberId", memberId)
            .bind("claimId", UUID.randomUUID())
            .bind("amount", amount)
            .bind("currency", currency)
            .fetch()
            .rowsUpdated()
            .contextWrite(TenantTestContext.put())
            .block(Duration.ofSeconds(5));
        return payableId;
    }

    /** Wait up to {@code timeout} for a single audit event on {@code medfund.audit.events}
     *  matching {@code entityType=CtcPayment} + {@code entityId} + {@code action}. */
    private JsonNode awaitAuditEvent(UUID entityId, String action, Duration timeout) {
        return pollAuditEvents(entityId, action, timeout, /* returnFirst */ true, null);
    }

    /** Count audit events matching {@code entityType=CtcPayment} + {@code entityId} +
     *  {@code action} within {@code timeout}. Drains the whole retained log — every
     *  matching event is counted. */
    private int countAuditEvents(UUID entityId, String action, Duration timeout) {
        int[] count = {0};
        pollAuditEvents(entityId, action, timeout, /* returnFirst */ false, count);
        return count[0];
    }

    private JsonNode pollAuditEvents(UUID entityId, String action, Duration timeout,
                                     boolean returnFirst, int[] countAcc) {
        Properties props = kafkaProps();

        long deadline = System.currentTimeMillis() + timeout.toMillis();
        String entityIdStr = entityId.toString();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of("medfund.audit.events"));
            while (System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> rec : records) {
                    JsonNode node;
                    try {
                        node = MAPPER.readTree(rec.value());
                    } catch (Exception ignored) {
                        continue;
                    }
                    if (!"CtcPayment".equals(node.path("entityType").asText())) continue;
                    if (!entityIdStr.equals(node.path("entityId").asText())) continue;
                    if (action != null && !action.equals(node.path("action").asText())) continue;
                    if (returnFirst) return node;
                    if (countAcc != null) countAcc[0]++;
                }
            }
        }
        return null;
    }

    /** Wait up to {@code timeout} for a matching event on the given topic — a
     *  small generalization of {@link #pollAuditEvents} so Phase-3 tests can
     *  assert against {@code medfund.finance.ctc.committed} and
     *  {@code medfund.finance.ctc.reversed} without cloning the boilerplate. */
    private JsonNode awaitTopicEvent(String topic,
                                     java.util.function.Predicate<JsonNode> match,
                                     Duration timeout) {
        Properties props = kafkaProps();
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            while (System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> rec : records) {
                    JsonNode node;
                    try {
                        node = MAPPER.readTree(rec.value());
                    } catch (Exception ignored) {
                        continue;
                    }
                    if (match.test(node)) return node;
                }
            }
        }
        return null;
    }

    private Properties kafkaProps() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "ctc-assert-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        return props;
    }
}

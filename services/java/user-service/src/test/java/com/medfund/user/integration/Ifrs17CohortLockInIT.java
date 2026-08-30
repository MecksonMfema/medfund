package com.medfund.user.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.shared.testfixtures.AbstractIntegrationTest;
import com.medfund.shared.testfixtures.TenantTestContext;
import com.medfund.shared.testfixtures.WithTenant;
import com.medfund.user.service.Ifrs17CohortLockInService;
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
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 15 §6 (I18) IT for {@link Ifrs17CohortLockInService}:
 * <ul>
 *   <li>First policy in a cohort captures the curve snapshot + timestamp, and
 *       fires an {@code AuditEvent} on {@code medfund.audit.events}.</li>
 *   <li>Second call for the same cohort is an idempotent no-op — no second
 *       audit event, no snapshot change.</li>
 *   <li>Cohort with no matching curve rows leaves {@code locked_in_at} NULL
 *       so a later curve upload can still lock it.</li>
 * </ul>
 *
 * <p>Test-migration folder + distinct flyway.table follow the Phase 4/5
 * precedent so this IT can co-exist with the other user-service ITs on
 * the shared JVM-scoped Postgres testcontainer without a boot-order race.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
    "spring.flyway.locations=classpath:db/cohort-lock-in-migration",
    "spring.flyway.baseline-on-migrate=true",
    "spring.flyway.baseline-version=0",
    "spring.flyway.table=flyway_history_lock_in",
})
@Import(Ifrs17CohortLockInIT.SecurityStub.class)
class Ifrs17CohortLockInIT extends AbstractIntegrationTest {

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

    static final String TENANT_ID = "00000000-0000-4000-8000-0000000000a1";
    static final String AUDIT_TOPIC = "medfund.audit.events";

    @Autowired private DatabaseClient db;
    @Autowired private Ifrs17CohortLockInService service;

    @MockBean private UserEventPublisher userEventPublisher;
    @MockBean private KeycloakSyncService keycloakSyncService;

    private UUID cohortId;
    private UUID portfolioId;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID TENANT_UUID = UUID.fromString(TENANT_ID);

    private JsonNode pollAuditForEntityId(String entityId, Duration timeout) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "audit-lock-in-" + UUID.randomUUID());
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
                                && "LOCK_IN_YIELD_CURVE".equals(node.path("action").asText())) {
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
        db.sql("DELETE FROM ifrs17_cohort").then().block(Duration.ofSeconds(10));
        db.sql("DELETE FROM ifrs17_portfolio").then().block(Duration.ofSeconds(10));
        db.sql("DELETE FROM public.tenant_yield_curve WHERE tenant_id = :t")
                .bind("t", TENANT_UUID)
                .then().block(Duration.ofSeconds(10));

        portfolioId = UUID.randomUUID();
        db.sql("INSERT INTO ifrs17_portfolio (id, name, insurance_line) VALUES (:id, 'LIFE-CORE', 'LIFE')")
                .bind("id", portfolioId).then().block(Duration.ofSeconds(10));

        cohortId = UUID.randomUUID();
        db.sql("""
                INSERT INTO ifrs17_cohort (id, portfolio_id, cohort_year, cohort_type, name)
                VALUES (:id, :pid, 2026, 'NON_ONEROUS', 'LIFE-2026-NON-ONEROUS')
                """)
                .bind("id", cohortId).bind("pid", portfolioId)
                .then().block(Duration.ofSeconds(10));
    }

    /**
     * Nullable-safe read of {@code locked_in_at} — the deferred lock-in test
     * expects a NULL and Reactor's {@code FluxHandle} rejects null emissions,
     * so the R2DBC {@code .map(...)} handler NPEs the moment the column
     * comes back null. Casting to text via {@code ::text} + COALESCE lets us
     * return an empty string for the null case; we parse back to Instant here.
     */
    private Instant readLockedInAt(UUID id) {
        String s = db.sql("SELECT COALESCE(locked_in_at::text, '') AS s FROM ifrs17_cohort WHERE id = :id")
                .bind("id", id)
                .map((r, m) -> r.get("s", String.class))
                .one().block(Duration.ofSeconds(10));
        if (s == null || s.isEmpty()) return null;
        // Postgres timestamptz text is e.g. "2026-08-28 14:09:03.123+00" — swap the space
        // to a T and normalize offset to be Instant-parseable.
        String iso = s.replace(' ', 'T');
        if (iso.matches(".*[+-]\\d{2}$")) iso = iso + ":00";
        return Instant.parse(iso.replace("+00:00", "Z"));
    }

    private void seedCurve(int tenorMonths, String spotRate) {
        db.sql("""
                INSERT INTO public.tenant_yield_curve
                    (tenant_id, currency, tenor_months, spot_rate, effective_from)
                VALUES (:tid, 'USD', :tm, :sr, DATE '2026-01-01')
                """)
                .bind("tid", TENANT_UUID)
                .bind("tm", tenorMonths)
                .bind("sr", new BigDecimal(spotRate))
                .then().block(Duration.ofSeconds(10));
    }

    @Test
    @WithTenant(TENANT_ID)
    void firstPolicy_writesSnapshotAndTimestampAndEmitsAudit() throws Exception {
        seedCurve(12, "0.075");
        seedCurve(60, "0.085");

        Boolean firstPolicy = service.lockInIfFirstPolicy(cohortId, "USD", LocalDate.of(2026, 6, 1))
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(15));

        assertThat(firstPolicy).as("first policy in the cohort should have written").isTrue();

        // Verify the row.
        String snapshotJson = db.sql("""
                SELECT locked_in_yield_curve_snapshot::text AS snap
                FROM ifrs17_cohort WHERE id = :id
                """)
                .bind("id", cohortId)
                .map((r, m) -> r.get("snap", String.class))
                .one().block(Duration.ofSeconds(10));
        // Postgres reformats JSONB on retrieval (spaces after colons); compare via a parser.
        JsonNode snapshot = MAPPER.readTree(snapshotJson);
        assertThat(snapshot.isArray()).isTrue();
        assertThat(snapshot.size()).isEqualTo(2);
        assertThat(snapshot.get(0).path("tenorMonths").asInt()).isEqualTo(12);
        assertThat(new BigDecimal(snapshot.get(0).path("spotRate").asText()))
                .isEqualByComparingTo("0.075");
        assertThat(snapshot.get(1).path("tenorMonths").asInt()).isEqualTo(60);
        assertThat(new BigDecimal(snapshot.get(1).path("spotRate").asText()))
                .isEqualByComparingTo("0.085");

        Instant lockedAt = readLockedInAt(cohortId);
        assertThat(lockedAt).isNotNull();

        JsonNode audit = pollAuditForEntityId(cohortId.toString(), Duration.ofSeconds(15));
        assertThat(audit).as("expected LOCK_IN_YIELD_CURVE audit event").isNotNull();
        assertThat(audit.path("entityType").asText()).isEqualTo("Ifrs17Cohort");
        assertThat(audit.path("action").asText()).isEqualTo("LOCK_IN_YIELD_CURVE");
        assertThat(audit.path("actorEmail").asText()).isEqualTo("system@medfund");
    }

    @Test
    @WithTenant(TENANT_ID)
    void secondCall_isIdempotentNoOp() {
        seedCurve(12, "0.075");

        Boolean first = service.lockInIfFirstPolicy(cohortId, "USD", LocalDate.of(2026, 6, 1))
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(15));
        assertThat(first).isTrue();

        Instant firstLockedAt = db.sql("SELECT locked_in_at FROM ifrs17_cohort WHERE id = :id")
                .bind("id", cohortId)
                .map((r, m) -> r.get("locked_in_at", Instant.class))
                .one().block(Duration.ofSeconds(10));

        Boolean second = service.lockInIfFirstPolicy(cohortId, "USD", LocalDate.of(2026, 9, 1))
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(15));
        assertThat(second).as("second call must be a no-op").isFalse();

        Instant secondLockedAt = db.sql("SELECT locked_in_at FROM ifrs17_cohort WHERE id = :id")
                .bind("id", cohortId)
                .map((r, m) -> r.get("locked_in_at", Instant.class))
                .one().block(Duration.ofSeconds(10));
        assertThat(secondLockedAt).isEqualTo(firstLockedAt);
    }

    @Test
    @WithTenant(TENANT_ID)
    void noMatchingCurveRows_defersLockIn() {
        // No seeded curve rows at all.
        Boolean wrote = service.lockInIfFirstPolicy(cohortId, "USD", LocalDate.of(2026, 6, 1))
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(15));
        assertThat(wrote).as("must not lock in with an empty snapshot").isFalse();

        Instant lockedAt = readLockedInAt(cohortId);
        assertThat(lockedAt).isNull();

        // Now seed a curve row and retry — should succeed.
        seedCurve(12, "0.075");
        Boolean second = service.lockInIfFirstPolicy(cohortId, "USD", LocalDate.of(2026, 6, 1))
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(15));
        assertThat(second).isTrue();
    }
}

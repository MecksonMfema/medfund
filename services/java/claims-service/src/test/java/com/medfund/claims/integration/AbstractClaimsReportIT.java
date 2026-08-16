package com.medfund.claims.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.shared.testfixtures.AbstractIntegrationTest;
import com.medfund.shared.testfixtures.TenantTestContext;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Base for the Phase 4 claims financial-report integration tests.
 *
 * <p>Boots the full claims-service application against the shared Postgres +
 * Kafka Testcontainers, migrates it with {@code db/test-migration} (see
 * V001__claims_report_it.sql), and drives the report endpoints over HTTP via
 * a {@link WebTestClient} bound to the random port.
 *
 * <p>Auth is stubbed at the JWT decoder: the {@link SecurityStub} returns a
 * {@code super_admin} token so {@link com.medfund.shared.security
 * .PermissionResolverFilter} bypasses the permission lookup entirely. The
 * {@code X-Tenant-ID} header satisfies {@code TenantWebFilter}; the report
 * stack reads the tenant configuration tables directly in {@code public}.
 *
 * <p>Report tables are TRUNCATEd between tests on the session user (the
 * {@code public_role} tenant role lacks TRUNCATE), then the tenant default
 * currency is reseeded. Seeds run on the tenant connection via
 * {@link TenantTestContext#put()} so the {@code SET ROLE public_role} path
 * is exercised too.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@TestPropertySource(properties = {
        "spring.flyway.locations=classpath:db/test-migration",
        "spring.flyway.baseline-on-migrate=true"
})
@Import(AbstractClaimsReportIT.SecurityStub.class)
public abstract class AbstractClaimsReportIT extends AbstractIntegrationTest {

    /** Tenant seeded by V001 — every fixture row carries this id. */
    public static final String TENANT_ID = "00000000-0000-4000-8000-000000000001";

    public static final String SECURITY_EVENTS_TOPIC = "medfund.security.events";
    public static final String REPORTING_CURRENCY_DEFAULT = "USD";

    protected static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    protected WebTestClient client;

    @Autowired
    protected DatabaseClient db;

    private static final AtomicInteger CLAIM_SEQ = new AtomicInteger(1);

    @BeforeEach
    void resetReportTables() {
        // No tenant context here on purpose — public_role cannot TRUNCATE.
        db.sql("""
                TRUNCATE claims, pre_authorizations, members, groups, schemes,
                         providers, rejection_reasons, tenant_high_cost_claimant_config,
                         tenant_currency_config, exchange_rates, tenant_report_config
                """)
                .fetch().rowsUpdated().block(Duration.ofSeconds(15));
        db.sql("""
                INSERT INTO tenant_currency_config (tenant_id, currency_code, is_default, is_active)
                VALUES (:tid::uuid, 'USD', TRUE, TRUE)
                """)
                .bind("tid", TENANT_ID)
                .fetch().rowsUpdated().block(Duration.ofSeconds(5));
        db.sql("""
                INSERT INTO rejection_reasons (code, category, description) VALUES
                    ('R01', 'ELIGIBILITY', 'Member not active'),
                    ('R02', 'WAITING_PERIOD', 'Within waiting period for benefit category')
                """)
                .fetch().rowsUpdated().block(Duration.ofSeconds(5));
    }

    // ── HTTP request helpers ─────────────────────────────────────────────
    protected WebTestClient.ResponseSpec get(String uri) {
        return client.get().uri(uri)
                .header("X-Tenant-ID", TENANT_ID)
                .headers(h -> h.setBearerAuth("it-token"))
                .exchange();
    }

    protected JsonNode getJson(String uri) {
        String body = get(uri)
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult().getResponseBody();
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            throw new RuntimeException("Non-JSON response from " + uri, e);
        }
    }

    protected byte[] getExcelBytes(String uri) {
        return get(uri)
                .expectStatus().isOk()
                .expectBody(byte[].class)
                .returnResult().getResponseBody();
    }

    protected static void assertDecimal(String expected, JsonNode actual) {
        org.junit.jupiter.api.Assertions.assertEquals(0,
                new BigDecimal(expected).compareTo(actual.decimalValue()),
                () -> "expected " + expected + " but was " + actual.asText());
    }

    // ── Seed helpers (tenant connection via TenantTestContext) ───────────
    protected UUID seedScheme(String name) {
        UUID id = UUID.randomUUID();
        db.sql("INSERT INTO schemes (id, name) VALUES (:id, :name)")
                .bind("id", id).bind("name", name)
                .fetch().rowsUpdated().contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(5));
        return id;
    }

    protected UUID seedProvider(String name) {
        UUID id = UUID.randomUUID();
        db.sql("INSERT INTO providers (id, name) VALUES (:id, :name)")
                .bind("id", id).bind("name", name)
                .fetch().rowsUpdated().contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(5));
        return id;
    }

    protected UUID seedGroup(String name) {
        UUID id = UUID.randomUUID();
        db.sql("INSERT INTO groups (id, name) VALUES (:id, :name)")
                .bind("id", id).bind("name", name)
                .fetch().rowsUpdated().contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(5));
        return id;
    }

    protected UUID seedMember(String first, String last, UUID groupId, UUID schemeId) {
        UUID id = UUID.randomUUID();
        db.sql("""
                INSERT INTO members (id, first_name, last_name, member_number, group_id, scheme_id, status)
                VALUES (:id, :first, :last, :mn, :gid, :sid, 'ACTIVE')
                """)
                .bind("id", id).bind("first", first).bind("last", last)
                .bind("mn", "M" + id.toString().substring(0, 8).toUpperCase())
                .bind("gid", groupId).bind("sid", schemeId)
                .fetch().rowsUpdated().contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(5));
        return id;
    }

    protected UUID seedClaim(UUID memberId, UUID providerId, UUID schemeId, String status,
                             String currency, BigDecimal claimed, BigDecimal approved, BigDecimal paid,
                             LocalDate serviceDate, LocalDate submissionDate, LocalDate adjudicatedAt) {
        return seedClaim(memberId, providerId, schemeId, status, currency,
                claimed, approved, paid, serviceDate, submissionDate, adjudicatedAt, "HEALTH");
    }

    protected UUID seedClaim(UUID memberId, UUID providerId, UUID schemeId, String status,
                             String currency, BigDecimal claimed, BigDecimal approved, BigDecimal paid,
                             LocalDate serviceDate, LocalDate submissionDate, LocalDate adjudicatedAt,
                             String insuranceLine) {
        UUID id = UUID.randomUUID();
        DatabaseClient.GenericExecuteSpec spec = db.sql("""
                INSERT INTO claims (id, claim_number, member_id, provider_id, scheme_id, status,
                                    currency_code, claimed_amount, approved_amount, paid_amount,
                                    service_date, submission_date, adjudicated_at, insurance_line)
                VALUES (:id, :cn, :mid, :pid, :sid, :status, :ccy, :claimed, :approved, :paid,
                        :svc, :sub, :adj, :line)
                """)
                .bind("id", id)
                .bind("cn", "CLM" + CLAIM_SEQ.getAndIncrement())
                .bind("mid", memberId)
                .bind("pid", providerId)
                .bind("sid", schemeId)
                .bind("status", status)
                .bind("ccy", currency)
                .bind("claimed", claimed)
                .bind("approved", approved)
                .bind("paid", paid)
                .bind("svc", serviceDate)
                .bind("sub", submissionDate.atStartOfDay())
                .bind("line", insuranceLine);
        if (adjudicatedAt != null) {
            spec = spec.bind("adj", adjudicatedAt.atStartOfDay());
        } else {
            spec = spec.bindNull("adj", LocalDateTime.class);
        }
        spec.fetch().rowsUpdated().contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(5));
        return id;
    }

    protected UUID seedPreAuth(UUID memberId, UUID providerId, String status, String currency,
                               BigDecimal requested, BigDecimal approved,
                               LocalDate requestedDate, LocalDate decisionDate) {
        UUID id = UUID.randomUUID();
        DatabaseClient.GenericExecuteSpec spec = db.sql("""
                INSERT INTO pre_authorizations (id, auth_number, member_id, provider_id, status,
                                                requested_amount, approved_amount, currency_code,
                                                requested_date, decision_date)
                VALUES (:id, :an, :mid, :pid, :status, :req, :appr, :ccy, :reqd, :decd)
                """)
                .bind("id", id)
                .bind("an", id.toString())
                .bind("mid", memberId)
                .bind("pid", providerId)
                .bind("status", status)
                .bind("req", requested)
                .bind("appr", approved)
                .bind("ccy", currency)
                .bind("reqd", requestedDate);
        if (decisionDate != null) {
            spec = spec.bind("decd", decisionDate);
        } else {
            spec = spec.bindNull("decd", LocalDate.class);
        }
        spec.fetch().rowsUpdated().contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(5));
        return id;
    }

    protected void setRejectionReason(UUID claimId, String code) {
        db.sql("UPDATE claims SET rejection_reason = :code WHERE id = :id")
                .bind("code", code).bind("id", claimId)
                .fetch().rowsUpdated().contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(5));
    }

    protected void upsertHighCostConfig(String currency, BigDecimal threshold) {
        db.sql("""
                INSERT INTO tenant_high_cost_claimant_config (tenant_id, threshold_amount, currency_code)
                VALUES (:tid::uuid, :threshold, :ccy)
                ON CONFLICT (tenant_id) DO UPDATE
                SET threshold_amount = EXCLUDED.threshold_amount, currency_code = EXCLUDED.currency_code
                """)
                .bind("tid", TENANT_ID).bind("threshold", threshold).bind("ccy", currency)
                .fetch().rowsUpdated().contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(5));
    }

    protected void seedExchangeRate(String base, String quote, BigDecimal rate, LocalDate rateDate) {
        db.sql("""
                INSERT INTO exchange_rates (base_currency, quote_currency, rate, rate_date, source, tenant_id)
                VALUES (:base, :quote, :rate, :rateDate, 'manual', :tid::uuid)
                ON CONFLICT (base_currency, quote_currency, rate_date, source, tenant_id) DO NOTHING
                """)
                .bind("base", base).bind("quote", quote).bind("rate", rate)
                .bind("rateDate", rateDate).bind("tid", TENANT_ID)
                .fetch().rowsUpdated().contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(5));
    }

    protected void setReportDisabled(String reportKey) {
        db.sql("""
                INSERT INTO tenant_report_config (tenant_id, report_key, enabled)
                VALUES (:tid::uuid, :key, FALSE)
                ON CONFLICT (tenant_id, report_key) DO UPDATE SET enabled = FALSE
                """)
                .bind("tid", TENANT_ID).bind("key", reportKey)
                .fetch().rowsUpdated().contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(5));
    }

    protected void setReportEnabled(String reportKey) {
        db.sql("""
                DELETE FROM tenant_report_config WHERE tenant_id = :tid::uuid AND report_key = :key
                """)
                .bind("tid", TENANT_ID).bind("key", reportKey)
                .fetch().rowsUpdated().contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(5));
    }

    // ── Security-event (audit) helpers ───────────────────────────────────
    protected List<JsonNode> securityEventsFor(String reportKey, Duration timeout) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "security-assert-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");

        List<JsonNode> matches = new ArrayList<>();
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(SECURITY_EVENTS_TOPIC));
            while (System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> rec : records) {
                    try {
                        JsonNode node = MAPPER.readTree(rec.value());
                        if (!"DATA_ACCESS".equals(node.path("eventType").asText())) continue;
                        String details = node.path("details").asText();
                        if (details == null || details.isBlank()) continue;
                        JsonNode det = MAPPER.readTree(details);
                        if (reportKey.equals(det.path("reportKey").asText())) {
                            matches.add(node);
                        }
                    } catch (Exception ignored) {
                        // non-JSON noise on the topic — ignore
                    }
                }
            }
        }
        return matches;
    }

    protected boolean securityEventPublished(String reportKey, Duration timeout) {
        return !securityEventsFor(reportKey, timeout).isEmpty();
    }

    protected int countSecurityEvents(String reportKey, Duration timeout) {
        return securityEventsFor(reportKey, timeout).size();
    }

    /** Stubbed JWT decoder — issues a super_admin token so permission checks are bypassed. */
    @TestConfiguration
    static class SecurityStub {
        @Bean
        ReactiveJwtDecoder reactiveJwtDecoder() {
            return token -> Mono.just(Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .claim("sub", "it-user")
                    .claim("email", "reports-it@medfund.example")
                    .claim("realm_access", Map.of("roles", List.of("super_admin")))
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(300))
                    .build());
        }
    }
}

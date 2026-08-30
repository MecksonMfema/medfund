package com.medfund.finance.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.finance.report.repository.ReportJobRepository;
import com.medfund.shared.report.ReportJobCompletedEvent;
import com.medfund.shared.testfixtures.AbstractIntegrationTest;
import com.medfund.shared.testfixtures.WithTenant;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end Phase 9 pipeline test. Uses Testcontainers Postgres + Kafka.
 *
 * <p>Boot-up: fresh finance-service context → the {@code ReportResultConsumer}
 * subscribes to {@code medfund.report.job-completed} (canonical only,
 * Phase 15 §22 rename Phase B cutover) in {@code @PostConstruct}.
 * Then the flow:
 * <ol>
 *   <li>POST {@code /api/v1/reports/actuarial/ibnr} → publisher lands a message
 *       on {@code job-requested} and the job row lands in Postgres with
 *       {@code status='requested'}.</li>
 *   <li>The test itself acts as the ai-service — it publishes a canned
 *       completed envelope to {@code job-completed}.</li>
 *   <li>The consumer updates the row to {@code status='completed'}; the test
 *       polls until the terminal write lands.</li>
 * </ol>
 *
 * <p>Also proves:
 * <ul>
 *   <li>Duplicate submits within the in-flight window return the same jobId
 *       via the {@code ux_arj_inflight} partial UNIQUE index.</li>
 *   <li>Cross-tenant poll on someone else's jobId returns 404 (Rule 2).</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "spring.flyway.locations=classpath:db/test-migration",
    "spring.flyway.baseline-on-migrate=true",
    "report.retention.enabled=false"
})
@Import(ActuarialJobFullPathIT.SecurityStub.class)
@WithTenant(ActuarialJobFullPathIT.TENANT)
class ActuarialJobFullPathIT extends AbstractIntegrationTest {

    static final String TENANT = "11111111-1111-1111-1111-111111111111";
    static final String OTHER_TENANT = "22222222-2222-2222-2222-222222222222";

    private static final MockWebServer CLAIMS = new MockWebServer();

    static {
        try {
            CLAIMS.start();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("failed to start Claims stub", e);
        }
    }

    @AfterAll
    static void shutdownStubs() throws java.io.IOException {
        CLAIMS.shutdown();
    }

    @DynamicPropertySource
    static void peerUrls(DynamicPropertyRegistry registry) {
        registry.add("services.claims.base-url", () -> CLAIMS.url("/").toString());
    }

    @Autowired private WebTestClient webTestClient;
    @Autowired private ReportJobRepository jobRepository;
    @Autowired private ObjectMapper objectMapper;

    @BeforeEach
    void resetClaimsStub() {
        // Empty ADJUDICATED page — shaping produces an empty triangle without
        // needing to fabricate claim history for every test.
        CLAIMS.setDispatcher(new okhttp3.mockwebserver.Dispatcher() {
            @Override
            public MockResponse dispatch(okhttp3.mockwebserver.RecordedRequest recordedRequest) {
                return new MockResponse()
                        .setBody("{\"content\":[],\"total\":0,\"page\":0,\"size\":100,\"totalPages\":0}")
                        .addHeader("Content-Type", "application/json");
            }
        });
    }

    @Test
    void submit_ibnr_persistsRowAndPublishesJobRequestedEvent() throws Exception {
        JsonNode submission = submit(ibnrBodyForLine("HEALTH"));
        UUID jobId = UUID.fromString(submission.get("jobId").asText());
        assertThat(submission.get("status").asText()).isEqualTo("requested");
        assertThat(submission.get("deduplicated").asBoolean()).isFalse();

        // Row landed for this tenant.
        assertThat(jobRepository.findById(jobId).block(Duration.ofSeconds(5)))
                .isNotNull()
                .satisfies(row -> {
                    assertThat(row.getTenantId()).isEqualTo(UUID.fromString(TENANT));
                    assertThat(row.getReportKey()).isEqualTo("IBNR_TRIANGLE");
                });

        // Phase 15 §22 Phase B cutover: publisher writes to the canonical
        // medfund.report.job-requested topic only; the legacy
        // medfund.actuarial.job-requested topic no longer receives records.
        JsonNode canonical = consumeMatching("medfund.report.job-requested",
                n -> jobId.toString().equals(n.path("jobId").asText()),
                Duration.ofSeconds(15));
        assertThat(canonical).as("canonical topic").isNotNull();
        assertThat(canonical.path("reportKey").asText()).isEqualTo("IBNR_TRIANGLE");
        assertThat(canonical.path("tenantId").asText()).isEqualTo(TENANT);

        JsonNode legacy = consumeMatching("medfund.actuarial.job-requested",
                n -> jobId.toString().equals(n.path("jobId").asText()),
                Duration.ofSeconds(3));
        assertThat(legacy).as("legacy topic must be dormant post-cutover").isNull();
    }

    @Test
    void completed_event_flipsRowToCompletedWithResultJson() throws Exception {
        JsonNode submission = submit(ibnrBodyForLine("LIFE"));
        UUID jobId = UUID.fromString(submission.get("jobId").asText());

        // Play the ai-service role — publish a completed envelope.
        publishCompleted(jobId, TENANT, "completed", canned(), null);

        // Poll /status until the consumer terminal-writes.
        await().atMost(30, TimeUnit.SECONDS).pollInterval(500, TimeUnit.MILLISECONDS).untilAsserted(() ->
            webTestClient.get()
                    .uri("/api/v1/reports/actuarial/jobs/" + jobId)
                    .header("X-Tenant-ID", TENANT)
                    .header("Authorization", "Bearer actuarial-user")
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.status").isEqualTo("completed")
                    .jsonPath("$.progressPct").isEqualTo(100)
                    .jsonPath("$.resultJson.ibnr_total").isEqualTo(1234.56)
        );
    }

    @Test
    void status_forOtherTenantJob_returnsNotFound() throws Exception {
        JsonNode submission = submit(ibnrBodyForLine("FUNERAL"));
        UUID jobId = UUID.fromString(submission.get("jobId").asText());

        webTestClient.get()
                .uri("/api/v1/reports/actuarial/jobs/" + jobId)
                .header("X-Tenant-ID", OTHER_TENANT)
                .header("Authorization", "Bearer actuarial-user")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void duplicate_submit_dedupes_via_inflight_partial_unique() throws Exception {
        JsonNode first = submit(ibnrBodyForLine("DISABILITY"));
        JsonNode second = submit(ibnrBodyForLine("DISABILITY"));

        assertThat(second.get("jobId").asText()).isEqualTo(first.get("jobId").asText());
        assertThat(second.get("deduplicated").asBoolean()).isTrue();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private JsonNode submit(String body) throws Exception {
        byte[] raw = webTestClient.post()
                .uri("/api/v1/reports/actuarial/ibnr")
                .header("X-Tenant-ID", TENANT)
                .header("Authorization", "Bearer actuarial-user")
                .header("Content-Type", "application/json")
                .bodyValue(body)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(byte[].class)
                .returnResult()
                .getResponseBody();
        assertThat(raw).isNotNull();
        return objectMapper.readTree(raw);
    }

    private void publishCompleted(UUID jobId, String tenantId, String status,
                                  Map<String, Object> result, String errorMessage) throws Exception {
        ReportJobCompletedEvent event = new ReportJobCompletedEvent(
                ReportJobCompletedEvent.CURRENT_SCHEMA_VERSION,
                jobId, UUID.fromString(tenantId), "IBNR_TRIANGLE",
                status, result, null, errorMessage,
                "chainladder-python:0.10.0", "volume",
                null, Instant.now().toString());
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            // Phase 15 §22 cutover: consumer subscribes to canonical only.
            producer.send(new ProducerRecord<>(
                    "medfund.report.job-completed",
                    jobId.toString(),
                    objectMapper.writeValueAsString(event))).get(5, TimeUnit.SECONDS);
        }
    }

    private JsonNode consumeMatching(String topic,
                                     java.util.function.Predicate<JsonNode> matcher,
                                     Duration timeout) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "actuarial-assert-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");

        long deadline = System.currentTimeMillis() + timeout.toMillis();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            while (System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    try {
                        JsonNode node = objectMapper.readTree(record.value());
                        if (matcher.test(node)) return node;
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        return null;
    }

    private static String ibnrBodyForLine(String insuranceLine) {
        return String.format("""
                {"periodStart":"2026-01-01","periodEnd":"2026-06-30","insuranceLine":"%s",
                 "shape":"paid","grain":"quarter","reportingCurrency":"USD","ldfMethod":"volume"}
                """, insuranceLine);
    }

    private static Map<String, Object> canned() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ldfs", List.of(1.5, 1.2, 1.05));
        r.put("cdf", List.of(1.89, 1.26, 1.05));
        r.put("ibnr_total", 1234.56);
        r.put("ultimate_total", 5678.90);
        r.put("mack_standard_error", 42.42);
        r.put("per_cohort_ultimate", List.of(2000.0, 1800.0, 1878.9));
        return r;
    }

    @TestConfiguration
    static class SecurityStub {
        @Bean
        ReactiveJwtDecoder reactiveJwtDecoder() {
            return token -> Mono.just(new Jwt(
                    token, Instant.now(), Instant.now().plusSeconds(300),
                    Map.of("alg", "none"),
                    Map.of("sub", "22222222-2222-2222-2222-222222222222", "iss", "test",
                            "email", "actor@example.test",
                            "realm_access", Map.of("roles",
                                    List.of("super_admin", "finance:view_reports")))));
        }
    }
}

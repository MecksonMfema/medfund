package com.medfund.finance.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.finance.ifrs17.dto.Ifrs17JobSubmissionResponse;
import com.medfund.finance.ifrs17.dto.Ifrs17ReportRequest;
import com.medfund.finance.ifrs17.service.Ifrs17JobService;
import com.medfund.finance.report.entity.ReportJob;
import com.medfund.finance.report.repository.ReportJobChunkRepository;
import com.medfund.finance.report.repository.ReportJobRepository;
import com.medfund.shared.report.ReportKey;
import com.medfund.shared.testfixtures.AbstractIntegrationTest;
import com.medfund.shared.testfixtures.WithTenant;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end wiring test for the Phase 15 §17 IFRS 17 orchestrator.
 *
 * <p>Uses Testcontainers Postgres + Kafka via {@link AbstractIntegrationTest};
 * stubs user-service portfolio + cohort endpoints via MockWebServer. Drives
 * {@link Ifrs17JobService#submit(ReportKey, Ifrs17ReportRequest, UUID, String,
 * String)} directly (skipping the HTTP layer — the controller wire-up is
 * verified by the compile-check for {@code @RequiresReport} + Swagger).
 *
 * <p>Covers:
 * <ul>
 *   <li>Fresh submit creates a parent {@code report_job} row + N chunk rows
 *       (one per portfolio × cohort × currency) and publishes N Kafka events
 *       to {@code medfund.report.job-requested}.</li>
 *   <li>Parent {@code retention_class = STATUTORY_7Y} (I28: IFRS 17 keys →
 *       REGULATORY family → 7-year statutory retention).</li>
 *   <li>IFRS17_MODEL rule fires per portfolio; the {@code measurement_model}
 *       lands in the chunk's {@code ifrs17Json} payload — falls back to PAA
 *       when no rule is loaded (industry default).</li>
 *   <li>Duplicate submit inside the in-flight window returns the same jobId
 *       via the {@code params_hash} dedupe.</li>
 * </ul>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.locations=classpath:db/test-migration",
        "spring.flyway.baseline-on-migrate=true",
        "report.retention.enabled=false"
})
@Import(Ifrs17JobServiceIT.SecurityStub.class)
@WithTenant(Ifrs17JobServiceIT.TENANT)
class Ifrs17JobServiceIT extends AbstractIntegrationTest {

    static final String TENANT = "11111111-1111-1111-1111-111111111111";
    static final UUID PORTFOLIO_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    static final UUID COHORT_A_2024 = UUID.fromString("cccccccc-0000-0000-0000-000000000001");
    static final UUID COHORT_A_2025 = UUID.fromString("cccccccc-0000-0000-0000-000000000002");

    private static final MockWebServer USER_SERVICE = new MockWebServer();

    static {
        try {
            USER_SERVICE.start();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("failed to start user-service stub", e);
        }
    }

    @AfterAll
    static void shutdown() throws java.io.IOException {
        USER_SERVICE.shutdown();
    }

    @DynamicPropertySource
    static void peerUrls(DynamicPropertyRegistry registry) {
        registry.add("services.user.base-url", () -> USER_SERVICE.url("/").toString());
    }

    @Autowired private Ifrs17JobService jobService;
    @Autowired private ReportJobRepository jobRepository;
    @Autowired private ReportJobChunkRepository chunkRepository;
    @Autowired private ObjectMapper objectMapper;

    @BeforeEach
    void resetStubs() {
        USER_SERVICE.setDispatcher(new Dispatcher() {
            @NotNull
            @Override
            public MockResponse dispatch(@NotNull RecordedRequest req) {
                String path = req.getPath() != null ? req.getPath() : "";
                if (path.startsWith("/api/v1/underwriting/portfolios")) {
                    return json(portfoliosJson());
                }
                if (path.startsWith("/api/v1/underwriting/cohorts")) {
                    if (path.contains("portfolioId=" + PORTFOLIO_A)) {
                        return json(cohortsForA());
                    }
                    return json("[]");
                }
                return new MockResponse().setResponseCode(404);
            }
        });
    }

    @Test
    void submit_createsParentAndFansChunksAndPublishesKafka() throws Exception {
        Ifrs17JobSubmissionResponse response = jobService.submit(
                        ReportKey.IFRS17_LRC_LIC_RECONCILIATION,
                        new Ifrs17ReportRequest(
                                LocalDate.of(2026, 1, 1),
                                LocalDate.of(2026, 3, 31),
                                null,
                                "USD"),
                        UUID.fromString(TENANT),
                        "22222222-2222-2222-2222-222222222222",
                        "actor@example.test")
                .block(Duration.ofSeconds(15));

        assertThat(response).isNotNull();
        assertThat(response.deduped()).isFalse();
        assertThat(response.chunkCount()).isEqualTo(2);  // portfolio A × 2 cohorts × 1 currency

        // Parent row landed with STATUTORY_7Y retention (IFRS 17 → REGULATORY family).
        ReportJob parent = jobRepository.findById(response.jobId()).block(Duration.ofSeconds(5));
        assertThat(parent).isNotNull();
        assertThat(parent.getReportKey()).isEqualTo("IFRS17_LRC_LIC_RECONCILIATION");
        assertThat(parent.getRetentionClass()).isEqualTo(ReportJob.RETENTION_STATUTORY_7Y);
        assertThat(parent.getStatus()).isEqualTo("requested");
        assertThat(parent.getTenantId()).isEqualTo(UUID.fromString(TENANT));

        // Chunk rows landed.
        List<UUID> chunkIds = chunkRepository.findByParentJobId(parent.getJobId())
                .map(c -> c.getChunkId())
                .collectList()
                .block(Duration.ofSeconds(5));
        assertThat(chunkIds).hasSize(2);

        // Kafka events fired — expect one per chunk on the canonical topic.
        Set<UUID> observed = consumeChunkEvents(response.jobId(), 2, Duration.ofSeconds(20));
        assertThat(observed).containsExactlyInAnyOrderElementsOf(new HashSet<>(chunkIds));
    }

    @Test
    void submit_duplicateWithinInflightWindow_returnsExistingJobId() {
        Ifrs17ReportRequest request = new Ifrs17ReportRequest(
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 6, 30), null, "USD");

        Ifrs17JobSubmissionResponse first = jobService.submit(
                        ReportKey.IFRS17_LRC_LIC_RECONCILIATION, request,
                        UUID.fromString(TENANT),
                        "22222222-2222-2222-2222-222222222222", "actor@example.test")
                .block(Duration.ofSeconds(15));
        Ifrs17JobSubmissionResponse second = jobService.submit(
                        ReportKey.IFRS17_LRC_LIC_RECONCILIATION, request,
                        UUID.fromString(TENANT),
                        "22222222-2222-2222-2222-222222222222", "actor@example.test")
                .block(Duration.ofSeconds(15));

        assertThat(second).isNotNull();
        assertThat(first).isNotNull();
        assertThat(second.jobId()).isEqualTo(first.jobId());
        assertThat(second.deduped()).isTrue();
        assertThat(second.chunkCount()).isEqualTo(first.chunkCount());
    }

    @Test
    void chunkPayload_carriesMeasurementModelDefault() throws Exception {
        Ifrs17JobSubmissionResponse response = jobService.submit(
                        ReportKey.IFRS17_INSURANCE_REVENUE_SERVICE_RESULT,
                        new Ifrs17ReportRequest(
                                LocalDate.of(2026, 7, 1),
                                LocalDate.of(2026, 9, 30),
                                null,
                                "USD"),
                        UUID.fromString(TENANT),
                        "22222222-2222-2222-2222-222222222222",
                        "actor@example.test")
                .block(Duration.ofSeconds(15));

        assertThat(response).isNotNull();
        // No IFRS17_MODEL rules loaded in the test tenant → the fallback default
        // (PAA / TIME / PL_ONLY) applies. Verify by consuming one chunk event
        // and inspecting the ifrs17Json slot.
        JsonNode event = consumeFirstChunkEvent(response.jobId(), Duration.ofSeconds(20));
        assertThat(event).isNotNull();
        JsonNode ifrs17 = event.get("ifrs17Json");
        assertThat(ifrs17).isNotNull();
        assertThat(ifrs17.get("measurement_model").asText()).isEqualTo("PAA");
        assertThat(ifrs17.get("finance_expense_presentation").asText()).isEqualTo("PL_ONLY");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String portfoliosJson() {
        return String.format("""
                [{"id":"%s","name":"Portfolio A","description":null,"insuranceLine":"HEALTH",
                  "isActive":true,"createdAt":"2024-01-01T00:00:00Z","updatedAt":"2024-01-01T00:00:00Z"}]
                """, PORTFOLIO_A);
    }

    private String cohortsForA() {
        return String.format("""
                [{"id":"%s","portfolioId":"%s","cohortYear":2024,"cohortType":"NON_ONEROUS",
                  "name":"2024","isActive":true,"createdAt":"2024-01-01T00:00:00Z","updatedAt":"2024-01-01T00:00:00Z"},
                 {"id":"%s","portfolioId":"%s","cohortYear":2025,"cohortType":"NON_ONEROUS",
                  "name":"2025","isActive":true,"createdAt":"2025-01-01T00:00:00Z","updatedAt":"2025-01-01T00:00:00Z"}]
                """, COHORT_A_2024, PORTFOLIO_A, COHORT_A_2025, PORTFOLIO_A);
    }

    private static MockResponse json(String body) {
        return new MockResponse()
                .setBody(body)
                .addHeader("Content-Type", "application/json");
    }

    private Set<UUID> consumeChunkEvents(UUID parentJobId, int expected, Duration timeout) throws Exception {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "ifrs17-assert-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");

        Set<UUID> observed = new HashSet<>();
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of("medfund.report.job-requested"));
            while (System.currentTimeMillis() < deadline && observed.size() < expected) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    JsonNode node = objectMapper.readTree(record.value());
                    String parent = node.path("parentJobId").asText(null);
                    if (parentJobId.toString().equals(parent)) {
                        observed.add(UUID.fromString(node.path("jobId").asText()));
                    }
                }
            }
        }
        return observed;
    }

    private JsonNode consumeFirstChunkEvent(UUID parentJobId, Duration timeout) throws Exception {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "ifrs17-first-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");

        long deadline = System.currentTimeMillis() + timeout.toMillis();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of("medfund.report.job-requested"));
            while (System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    JsonNode node = objectMapper.readTree(record.value());
                    if (parentJobId.toString().equals(node.path("parentJobId").asText(null))) {
                        return node;
                    }
                }
            }
        }
        return null;
    }

    @TestConfiguration
    static class SecurityStub {
        @Bean
        ReactiveJwtDecoder reactiveJwtDecoder() {
            return token -> Mono.just(new Jwt(
                    token, Instant.now(), Instant.now().plusSeconds(300),
                    Map.of("alg", "none"),
                    Map.of("sub", "22222222-2222-2222-2222-222222222222",
                            "email", "actor@example.test",
                            "realm_access", Map.of("roles", List.of("super_admin")))));
        }
    }
}

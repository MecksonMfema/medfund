package com.medfund.finance.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.finance.ifrs17.service.Ifrs17JobAggregator;
import com.medfund.finance.report.entity.ReportJob;
import com.medfund.finance.report.entity.ReportJobChunk;
import com.medfund.finance.report.repository.ReportJobChunkRepository;
import com.medfund.finance.report.repository.ReportJobRepository;
import com.medfund.shared.report.ReportJobCompletedEvent;
import com.medfund.shared.testfixtures.AbstractIntegrationTest;
import com.medfund.shared.testfixtures.WithTenant;
import io.r2dbc.postgresql.codec.Json;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * IT for {@link Ifrs17JobAggregator}. Drives the aggregator directly (skipping
 * the Kafka receiver loop; that path is exercised by
 * {@link ActuarialJobFullPathIT}'s dual-topic consumer). Verifies:
 *
 * <ul>
 *   <li>Publishing N chunk-completed events aggregates once the last one
 *       lands; parent's {@code result_json} carries the envelope with
 *       per-portfolio × cohort × currency grouping.</li>
 *   <li>Parent stays {@code requested} until every chunk is terminal.</li>
 *   <li>Duplicate events on the same chunk_id are idempotent — the V151
 *       append-only trigger stays quiet.</li>
 *   <li>Cross-tenant event → aggregator raises → consumer's error path
 *       would ack + log (Rule 2).</li>
 * </ul>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.locations=classpath:db/test-migration",
        "spring.flyway.baseline-on-migrate=true",
        "report.retention.enabled=false"
})
@Import(Ifrs17JobAggregatorIT.SecurityStub.class)
@WithTenant(Ifrs17JobAggregatorIT.TENANT)
class Ifrs17JobAggregatorIT extends AbstractIntegrationTest {

    static final String TENANT = "11111111-1111-1111-1111-111111111111";
    static final UUID OTHER_TENANT = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Autowired private Ifrs17JobAggregator aggregator;
    @Autowired private ReportJobRepository jobRepository;
    @Autowired private ReportJobChunkRepository chunkRepository;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void aggregates_parentOnceEveryChunkTerminal_andEnvelopeShapeMatches() throws Exception {
        UUID parentId = insertParent("IFRS17_LRC_LIC_RECONCILIATION");
        UUID chunkA = insertChunk(parentId, UUID.randomUUID(), UUID.randomUUID(), "USD");
        UUID chunkB = insertChunk(parentId, UUID.randomUUID(), UUID.randomUUID(), "ZWL");

        // First chunk completes → parent still requested (waiting on the other).
        aggregator.processChunkResult(loadChunk(chunkA), completedEvent(chunkA, "IFRS17_LRC_LIC_RECONCILIATION",
                        Map.of("model", "PAA", "lrc", 1000)))
                .block(Duration.ofSeconds(5));
        assertThat(jobRepository.findById(parentId).block(Duration.ofSeconds(5)).getStatus())
                .isEqualTo("requested");

        // Second (last) chunk completes → parent aggregates.
        aggregator.processChunkResult(loadChunk(chunkB), completedEvent(chunkB, "IFRS17_LRC_LIC_RECONCILIATION",
                        Map.of("model", "GMM", "lrc", 500)))
                .block(Duration.ofSeconds(5));

        ReportJob parent = jobRepository.findById(parentId).block(Duration.ofSeconds(5));
        assertThat(parent.getStatus()).isEqualTo("completed");
        assertThat(parent.getCompletedAt()).isNotNull();

        JsonNode envelope = objectMapper.readTree(parent.getResultJson().asString());
        assertThat(envelope.get("summary").get("totalChunks").asInt()).isEqualTo(2);
        assertThat(envelope.get("summary").get("completedChunks").asInt()).isEqualTo(2);
        assertThat(envelope.get("summary").get("failedChunks").asInt()).isEqualTo(0);
        // Both PAA and GMM appear because each chunk's result carries its
        // own model discriminator (the shaping service fires IFRS17_MODEL
        // per portfolio, so a mixed-model parent is a valid state).
        JsonNode models = envelope.get("summary").get("measurementModelsSeen");
        assertThat(models.isArray()).isTrue();
        assertThat(models.size()).isEqualTo(2);
        assertThat(envelope.get("summary").get("currenciesSeen").isArray()).isTrue();
        assertThat(envelope.get("summary").get("currenciesSeen").size()).isEqualTo(2);
        assertThat(envelope.get("portfolios").isObject()).isTrue();
    }

    @Test
    void anyChunkFailed_flipsParentToFailed_envelopeStillAggregates() throws Exception {
        UUID parentId = insertParent("IFRS17_INSURANCE_REVENUE_SERVICE_RESULT");
        UUID chunkA = insertChunk(parentId, UUID.randomUUID(), UUID.randomUUID(), "USD");
        UUID chunkB = insertChunk(parentId, UUID.randomUUID(), UUID.randomUUID(), "USD");

        aggregator.processChunkResult(loadChunk(chunkA), completedEvent(chunkA,
                        "IFRS17_INSURANCE_REVENUE_SERVICE_RESULT",
                        Map.of("model", "PAA")))
                .block(Duration.ofSeconds(5));
        aggregator.processChunkResult(loadChunk(chunkB), failedEvent(chunkB,
                        "IFRS17_INSURANCE_REVENUE_SERVICE_RESULT",
                        "compute divergence: BEL projection horizon exceeded"))
                .block(Duration.ofSeconds(5));

        ReportJob parent = jobRepository.findById(parentId).block(Duration.ofSeconds(5));
        assertThat(parent.getStatus()).isEqualTo("failed");
        JsonNode envelope = objectMapper.readTree(parent.getResultJson().asString());
        assertThat(envelope.get("summary").get("completedChunks").asInt()).isEqualTo(1);
        assertThat(envelope.get("summary").get("failedChunks").asInt()).isEqualTo(1);
    }

    @Test
    void duplicateChunkEvent_isIdempotent_noSecondUpdate() {
        UUID parentId = insertParent("IFRS17_LRC_LIC_RECONCILIATION");
        UUID chunkA = insertChunk(parentId, UUID.randomUUID(), UUID.randomUUID(), "USD");

        // First delivery lands the terminal state.
        aggregator.processChunkResult(loadChunk(chunkA), completedEvent(chunkA,
                        "IFRS17_LRC_LIC_RECONCILIATION", Map.of("model", "PAA")))
                .block(Duration.ofSeconds(5));
        OffsetDateTime firstCompletedAt = loadChunk(chunkA).getCompletedAt();

        // Second delivery (dual-topic echo) is a no-op — same terminal status,
        // same completed_at, no second UPDATE.
        aggregator.processChunkResult(loadChunk(chunkA), completedEvent(chunkA,
                        "IFRS17_LRC_LIC_RECONCILIATION", Map.of("model", "PAA")))
                .block(Duration.ofSeconds(5));

        assertThat(loadChunk(chunkA).getCompletedAt()).isEqualTo(firstCompletedAt);
        // Parent should be terminal exactly once — a second aggregation would
        // trigger the V151 append-only guard.
        ReportJob parent = jobRepository.findById(parentId).block(Duration.ofSeconds(5));
        assertThat(parent.getStatus()).isEqualTo("completed");
    }

    @Test
    void crossTenantEvent_isRejected_parentUnchanged() {
        UUID parentId = insertParent("IFRS17_LRC_LIC_RECONCILIATION");
        UUID chunkA = insertChunk(parentId, UUID.randomUUID(), UUID.randomUUID(), "USD");

        ReportJobCompletedEvent spoofed = new ReportJobCompletedEvent(
                ReportJobCompletedEvent.CURRENT_SCHEMA_VERSION,
                chunkA,
                OTHER_TENANT,   // wrong tenant
                "IFRS17_LRC_LIC_RECONCILIATION",
                "completed",
                Map.of("model", "PAA"),
                null, null, "ifrs17-paa-1.0", "PAA", null, Instant.now().toString());

        assertThatThrownBy(() -> aggregator.processChunkResult(loadChunk(chunkA), spoofed)
                        .block(Duration.ofSeconds(5)))
                .hasMessageContaining("Tenant mismatch");

        // Parent + chunk both untouched.
        assertThat(jobRepository.findById(parentId).block(Duration.ofSeconds(5)).getStatus())
                .isEqualTo("requested");
        assertThat(loadChunk(chunkA).getStatus()).isEqualTo("requested");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private UUID insertParent(String reportKey) {
        ReportJob parent = new ReportJob();
        parent.setTenantId(UUID.fromString(TENANT));
        parent.setReportKey(reportKey);
        parent.setStatus("requested");
        parent.setParamsJson(Json.of("{}"));
        parent.setParamsHash("test-" + UUID.randomUUID());
        parent.setRequestedAt(OffsetDateTime.now());
        parent.setRequestedByEmail("actor@example.test");  // NOT NULL on report_job
        parent.setRetentionClass(ReportJob.RETENTION_STATUTORY_7Y);
        return jobRepository.save(parent).block(Duration.ofSeconds(5)).getJobId();
    }

    private UUID insertChunk(UUID parentId, UUID portfolioId, UUID cohortId, String currency) {
        ReportJobChunk chunk = new ReportJobChunk();
        chunk.setParentJobId(parentId);
        chunk.setPortfolioId(portfolioId);
        chunk.setCohortId(cohortId);
        chunk.setCurrency(currency);
        chunk.setStatus("requested");
        chunk.setParamsJson(Json.of("{\"pending\":true}"));
        chunk.setRequestedAt(OffsetDateTime.now());
        return chunkRepository.save(chunk).block(Duration.ofSeconds(5)).getChunkId();
    }

    private ReportJobChunk loadChunk(UUID chunkId) {
        return chunkRepository.findById(chunkId).block(Duration.ofSeconds(5));
    }

    private ReportJobCompletedEvent completedEvent(UUID chunkId, String reportKey,
                                                    Map<String, Object> result) {
        Map<String, Object> body = new LinkedHashMap<>(result);
        return new ReportJobCompletedEvent(
                ReportJobCompletedEvent.CURRENT_SCHEMA_VERSION,
                chunkId,
                UUID.fromString(TENANT),
                reportKey,
                "completed",
                body,
                null, null,
                "ifrs17-1.0",
                (String) body.getOrDefault("model", "PAA"),
                null,
                Instant.now().toString());
    }

    private ReportJobCompletedEvent failedEvent(UUID chunkId, String reportKey, String error) {
        return new ReportJobCompletedEvent(
                ReportJobCompletedEvent.CURRENT_SCHEMA_VERSION,
                chunkId,
                UUID.fromString(TENANT),
                reportKey,
                "failed",
                null, null, error, "ifrs17-1.0", "PAA", null, Instant.now().toString());
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

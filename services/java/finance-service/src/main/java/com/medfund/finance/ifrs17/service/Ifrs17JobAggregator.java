package com.medfund.finance.ifrs17.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.finance.ifrs17.kafka.Ifrs17MaterialEventPublisher;
import com.medfund.finance.report.entity.ReportJob;
import com.medfund.finance.report.entity.ReportJobChunk;
import com.medfund.finance.report.repository.ReportJobChunkRepository;
import com.medfund.finance.report.repository.ReportJobRepository;
import com.medfund.shared.report.ReportJobCompletedEvent;
import io.r2dbc.postgresql.codec.Json;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * IFRS 17 chunk-result aggregator (Phase 15 §18). Consumes chunk terminal
 * events (routed by {@link com.medfund.finance.report.kafka.ReportResultConsumer}
 * when {@link ReportJobCompletedEvent#jobId()} matches a
 * {@code report_job_chunk} row), writes the chunk's terminal status, and —
 * when every chunk under the parent is terminal — aggregates chunk results
 * into the parent's {@code result_json} and marks the parent completed
 * (or failed if any chunk failed).
 *
 * <h2>Envelope shape (per I13)</h2>
 * The aggregated JSON stored in {@code report_job.result_json} groups chunks by
 * portfolio → cohort → currency; each leaf carries the chunk's raw compute
 * output (movement journals + measurement model discriminator + warnings). A
 * top-level {@code summary} block carries the roll-up:
 *
 * <pre>{@code
 * {
 *   "summary": {
 *     "totalChunks": 6,
 *     "completedChunks": 5,
 *     "failedChunks": 1,
 *     "measurementModelsSeen": ["PAA", "GMM"],
 *     "currenciesSeen": ["USD", "ZWL"]
 *   },
 *   "portfolios": {
 *     "<portfolio-id>": {
 *       "cohorts": {
 *         "<cohort-id>": {
 *           "<currency>": { ...chunk result... }
 *         }
 *       }
 *     }
 *   }
 * }
 * }</pre>
 *
 * <h2>Idempotency</h2>
 * <ul>
 *   <li>Chunk update short-circuits when the row is already terminal — dual-topic
 *       delivery during the rename window means we can see the same event twice.</li>
 *   <li>Parent aggregation short-circuits when the parent is already terminal —
 *       a late duplicate on the last-chunk event would otherwise try to update
 *       the parent twice (and the V151 append-only trigger would reject).</li>
 * </ul>
 *
 * <h2>Rule-2 guard</h2>
 * The chunk's parent is loaded to verify tenant match against the event
 * payload — a spoofed cross-tenant event is rejected and the caller (the
 * consumer's error path) acks + logs to avoid poison-pill loops.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Ifrs17JobAggregator {

    private static final List<String> TERMINAL_STATUSES = List.of("completed", "failed");

    private final ReportJobChunkRepository chunkRepository;
    private final ReportJobRepository jobRepository;
    private final ObjectMapper objectMapper;
    private final Ifrs17MaterialEventPublisher materialEventPublisher;

    /**
     * Route entry from the shared result consumer. The passed
     * {@code (chunk, event)} pair reflects a chunk row already located by the
     * consumer's parent-fallback path — this method just applies the terminal
     * update + aggregates when the parent hits its final chunk.
     */
    public Mono<Void> processChunkResult(ReportJobChunk chunk, ReportJobCompletedEvent event) {
        // Rule-2 tenant match — the parent's tenantId is the source of truth.
        // We load it up-front so the check runs before any write.
        return jobRepository.findById(chunk.getParentJobId())
                .switchIfEmpty(Mono.error(new IllegalStateException(
                        "chunk " + chunk.getChunkId() + " references missing parent " + chunk.getParentJobId())))
                .flatMap(parent -> {
                    if (!parent.getTenantId().equals(event.tenantId())) {
                        return Mono.error(new IllegalStateException(
                                "Tenant mismatch on chunk-completed event: parent=" + parent.getTenantId()
                                        + " event=" + event.tenantId() + " chunkId=" + event.jobId()));
                    }
                    return applyChunkTerminal(chunk, event)
                            .flatMap(persisted -> maybeAggregateParent(parent));
                });
    }

    private Mono<ReportJobChunk> applyChunkTerminal(ReportJobChunk chunk, ReportJobCompletedEvent event) {
        // Idempotency: chunk already terminal → skip. Same guard the consumer
        // uses on parent rows for the dual-topic dedup path.
        if (isTerminal(chunk.getStatus())) {
            log.debug("[ifrs17-aggregator] duplicate terminal event for chunk {} — skipping",
                    chunk.getChunkId());
            return Mono.just(chunk);
        }
        chunk.setStatus(event.status());
        chunk.setCompletedAt(OffsetDateTime.now());
        if (event.errorMessage() != null) {
            chunk.setErrorMessage(event.errorMessage());
        }
        if (event.resultJson() != null) {
            try {
                chunk.setResultJson(Json.of(objectMapper.writeValueAsString(event.resultJson())));
            } catch (JsonProcessingException e) {
                return Mono.error(new IllegalStateException(
                        "Failed to serialise resultJson for chunk " + event.jobId(), e));
            }
        }
        if (event.payloadRef() != null) {
            chunk.setResultRef(event.payloadRef());
        }
        return chunkRepository.save(chunk);
    }

    private Mono<Void> maybeAggregateParent(ReportJob parent) {
        // Idempotency: parent already terminal → skip aggregation. A late
        // duplicate on the last chunk would otherwise poke the V151 trigger.
        if (isTerminal(parent.getStatus())) {
            log.debug("[ifrs17-aggregator] parent {} already terminal — skipping aggregation",
                    parent.getJobId());
            return Mono.empty();
        }
        return chunkRepository.findByParentJobId(parent.getJobId())
                .collectList()
                .flatMap(chunks -> {
                    if (!allTerminal(chunks)) {
                        log.debug("[ifrs17-aggregator] parent {} has {} chunks; {} still non-terminal — "
                                        + "waiting for the rest",
                                parent.getJobId(), chunks.size(), countNonTerminal(chunks));
                        return Mono.empty();
                    }
                    return finalizeParent(parent, chunks);
                });
    }

    private Mono<Void> finalizeParent(ReportJob parent, List<ReportJobChunk> chunks) {
        Map<String, Object> envelope = buildEnvelope(chunks);
        try {
            parent.setResultJson(Json.of(objectMapper.writeValueAsString(envelope)));
        } catch (JsonProcessingException e) {
            return Mono.error(new IllegalStateException(
                    "Failed to serialise aggregated envelope for parent " + parent.getJobId(), e));
        }
        parent.setStatus(chunks.stream().anyMatch(c -> "failed".equals(c.getStatus()))
                ? "failed"
                : "completed");
        parent.setCompletedAt(OffsetDateTime.now());
        log.info("[ifrs17-aggregator] parent {} finalised as {} ({} chunks)",
                parent.getJobId(), parent.getStatus(), chunks.size());
        return jobRepository.save(parent)
                .then(publishMaterialEvents(parent, chunks));
    }

    /**
     * Phase 15 §19 wire-up: scans finalized chunk results for material
     * signals and fans out {@code medfund.ifrs17.material-event} per finding.
     * Best-effort — a publish failure is logged inside the publisher and
     * never blocks the aggregator's commit (the parent row is already saved
     * by the time this runs).
     *
     * <p>Signals recognised:
     * <ul>
     *   <li>{@code csmNegative=true} in a chunk's parsed result → CSM_NEGATIVE</li>
     *   <li>{@code lockedInCurveFallback=true} → LOCKED_IN_CURVE_FALLBACK</li>
     *   <li>{@code openingBalanceAutoDerived=true} → OPENING_BALANCE_AUTO_DERIVED</li>
     * </ul>
     * ONEROUS_TRANSITION and IBNR_SUB_JOB_STALE fire from other services (user
     * and finance IBNR orchestrator respectively) — not aggregator-scanned.
     */
    private Mono<Void> publishMaterialEvents(ReportJob parent, List<ReportJobChunk> chunks) {
        String tenantId = parent.getTenantId() != null ? parent.getTenantId().toString() : null;
        UUID sourceRunId = parent.getJobId();
        return Flux.fromIterable(chunks)
                .filter(c -> "completed".equals(c.getStatus()))
                .flatMap(chunk -> {
                    Map<String, Object> result = parseChunkResult(chunk);
                    Object inner = result.get("result");
                    if (!(inner instanceof Map<?, ?> innerMap)) {
                        return Mono.empty();
                    }
                    Mono<Void> pub = Mono.empty();
                    if (Boolean.TRUE.equals(innerMap.get("csmNegative"))) {
                        pub = pub.then(materialEventPublisher.publish(
                                tenantId, chunk.getCohortId(), "CSM_NEGATIVE", "WARN",
                                "CSM went negative on cohort " + chunk.getCohortId()
                                        + " during " + chunk.getCurrency() + " compute",
                                sourceRunId));
                    }
                    if (Boolean.TRUE.equals(innerMap.get("lockedInCurveFallback"))) {
                        pub = pub.then(materialEventPublisher.publish(
                                tenantId, chunk.getCohortId(), "LOCKED_IN_CURVE_FALLBACK",
                                "WARN",
                                "Locked-in yield curve fallback used for cohort "
                                        + chunk.getCohortId(),
                                sourceRunId));
                    }
                    if (Boolean.TRUE.equals(innerMap.get("openingBalanceAutoDerived"))) {
                        pub = pub.then(materialEventPublisher.publish(
                                tenantId, chunk.getCohortId(), "OPENING_BALANCE_AUTO_DERIVED",
                                "INFO",
                                "Opening balance auto-derived (no admin seed) for cohort "
                                        + chunk.getCohortId(),
                                sourceRunId));
                    }
                    return pub;
                })
                .then();
    }

    /** Package-private for direct-invocation unit tests. */
    Map<String, Object> buildEnvelope(List<ReportJobChunk> chunks) {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> summary = new LinkedHashMap<>();
        int completed = 0;
        int failed = 0;
        List<String> models = new ArrayList<>();
        List<String> currencies = new ArrayList<>();

        Map<String, Object> portfolios = new LinkedHashMap<>();
        for (ReportJobChunk chunk : chunks) {
            if ("completed".equals(chunk.getStatus())) completed++;
            if ("failed".equals(chunk.getStatus())) failed++;
            if (chunk.getCurrency() != null && !currencies.contains(chunk.getCurrency())) {
                currencies.add(chunk.getCurrency());
            }

            Map<String, Object> chunkResult = parseChunkResult(chunk);
            Object model = chunkResult.get("model");
            if (model instanceof String modelName && !models.contains(modelName)) {
                models.add(modelName);
            }

            // portfolios[portfolioId].cohorts[cohortId][currency] = chunkResult
            String portfolioKey = String.valueOf(chunk.getPortfolioId());
            String cohortKey = String.valueOf(chunk.getCohortId());
            String currencyKey = chunk.getCurrency() != null ? chunk.getCurrency() : "UNKNOWN";

            @SuppressWarnings("unchecked")
            Map<String, Object> portfolioNode = (Map<String, Object>) portfolios.computeIfAbsent(
                    portfolioKey, k -> {
                        Map<String, Object> node = new LinkedHashMap<>();
                        node.put("cohorts", new LinkedHashMap<String, Object>());
                        return node;
                    });
            @SuppressWarnings("unchecked")
            Map<String, Object> cohorts = (Map<String, Object>) portfolioNode.get("cohorts");
            @SuppressWarnings("unchecked")
            Map<String, Object> cohortNode = (Map<String, Object>) cohorts.computeIfAbsent(
                    cohortKey, k -> new LinkedHashMap<String, Object>());
            cohortNode.put(currencyKey, chunkResult);
        }

        summary.put("totalChunks", chunks.size());
        summary.put("completedChunks", completed);
        summary.put("failedChunks", failed);
        summary.put("measurementModelsSeen", models);
        summary.put("currenciesSeen", currencies);

        root.put("summary", summary);
        root.put("portfolios", portfolios);
        return root;
    }

    private Map<String, Object> parseChunkResult(ReportJobChunk chunk) {
        Map<String, Object> leaf = new LinkedHashMap<>();
        leaf.put("chunkId", chunk.getChunkId().toString());
        leaf.put("status", chunk.getStatus());
        if (chunk.getErrorMessage() != null) {
            leaf.put("error", chunk.getErrorMessage());
        }
        if (chunk.getResultRef() != null) {
            leaf.put("resultRef", chunk.getResultRef());
        }
        if (chunk.getResultJson() != null) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = objectMapper.readValue(
                        chunk.getResultJson().asString(), Map.class);
                leaf.put("result", parsed);
                // Bubble the compute-side ``model`` up to the leaf key for the
                // summary discriminator — the aggregator's summary block reads
                // it back via leaf.get("model").
                if (parsed.get("model") instanceof String modelName) {
                    leaf.put("model", modelName);
                }
            } catch (Exception e) {
                log.warn("[ifrs17-aggregator] failed to parse chunk {} result_json: {}",
                        chunk.getChunkId(), e.getMessage());
                leaf.put("result", null);
            }
        }
        return leaf;
    }

    private boolean allTerminal(List<ReportJobChunk> chunks) {
        return !chunks.isEmpty() && chunks.stream().allMatch(c -> isTerminal(c.getStatus()));
    }

    private long countNonTerminal(List<ReportJobChunk> chunks) {
        return chunks.stream().filter(c -> !isTerminal(c.getStatus())).count();
    }

    private static boolean isTerminal(String status) {
        return TERMINAL_STATUSES.contains(status);
    }

    /**
     * Bridge used by the consumer's routing path — locates a chunk row by the
     * event's {@code jobId} (which is the chunk_id per Phase 17's payload
     * contract). Returns empty when no chunk matches — the consumer then
     * treats the event as a parent-row event and continues its existing path.
     */
    public Mono<Optional<ReportJobChunk>> findChunk(UUID chunkId) {
        return chunkRepository.findById(chunkId)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty());
    }
}

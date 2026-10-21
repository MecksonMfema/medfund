package com.medfund.finance.producer.service;

import com.medfund.finance.producer.entity.Producer;
import com.medfund.finance.producer.entity.ProducerBackfillCandidate;
import com.medfund.finance.producer.repository.ProducerBackfillCandidateRepository;
import com.medfund.finance.producer.repository.ProducerRepository;
import com.medfund.finance.reinsurance.entity.Treaty;
import com.medfund.finance.reinsurance.repository.TreatyRepository;
import com.medfund.shared.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Ships the reinsurance R15 handoff: backfill the {@code treaty.producer_id}
 * FK by fuzzy-matching the pre-existing {@code treaty.producer_ref} free-text
 * field to {@code producer.name}. Mirrors
 * {@link com.medfund.finance.reinsurance.service.TreatyActivationBackfillJob}
 * in shape.
 *
 * <p>Per treaty: fuzzy-match against every active producer via Levenshtein
 * similarity (normalised to [0, 1]). Persist a
 * {@link ProducerBackfillCandidate} row per plausible match (top 3 producers
 * by score, min 0.500). Candidates ≥ 0.900 auto-accept — the treaty's
 * {@code producer_id} is set immediately and the candidate is marked ACCEPTED.
 *
 * <p>Idempotency: the {@code ux_pbc_treaty_candidate} partial UNIQUE index
 * makes a rerun write zero duplicate candidate rows. A rerun that finds
 * treaties whose {@code producer_id} is already set skips them entirely via
 * {@link TreatyRepository#findByProducerRefIsNotNullAndProducerIdIsNull()}.
 *
 * <p>On-demand invocation via {@code POST /api/v1/producers/backfill/run} —
 * not scheduled. Uses raw {@code java.lang.CharSequence} Levenshtein rather
 * than {@code org.apache.commons.text} to avoid the extra dependency.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProducerBackfillJob {

    static final BigDecimal AUTO_ACCEPT_THRESHOLD = new BigDecimal("0.900");
    static final BigDecimal MIN_CANDIDATE_SCORE  = new BigDecimal("0.500");
    static final int BATCH_SIZE = 200;
    static final int TOP_K_CANDIDATES = 3;

    static final String STRATEGY_LEVENSHTEIN = "LEVENSHTEIN";
    static final String STATUS_PENDING       = "PENDING";
    static final String STATUS_ACCEPTED      = "ACCEPTED";

    private final TreatyRepository treatyRepository;
    private final ProducerRepository producerRepository;
    private final ProducerBackfillCandidateRepository candidateRepository;
    private final ProducerBackfillProgressService progressService;

    /**
     * Reactive entry point. Fetches the active producer list once, then
     * streams treaties with an unresolved {@code producer_ref} in batches of
     * {@code BATCH_SIZE} and scores each treaty against every producer.
     */
    public Mono<Void> runBackfill(String actorId, String actorEmail) {
        return Mono.deferContextual(ctx -> {
            UUID tenantId = tenantUuidFrom(ctx);
            progressService.start(tenantId);
            return producerRepository.findActiveOrderByName().collectList()
                    .flatMap(producers -> treatyRepository.findByProducerRefIsNotNullAndProducerIdIsNull()
                            .buffer(BATCH_SIZE)
                            .concatMap(batch -> processBatch(tenantId, batch, producers,
                                    actorId, actorEmail))
                            .then())
                    .doOnSuccess(v -> progressService.complete(tenantId))
                    .doOnError(e -> progressService.fail(tenantId,
                            e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        });
    }

    Mono<Void> processBatch(UUID tenantId, List<Treaty> batch, List<Producer> producers,
                            String actorId, String actorEmail) {
        return Flux.fromIterable(batch)
                .concatMap(treaty -> matchAndPersist(tenantId, treaty, producers,
                        actorId, actorEmail))
                .then();
    }

    private Mono<Void> matchAndPersist(UUID tenantId, Treaty treaty, List<Producer> producers,
                                        String actorId, String actorEmail) {
        String ref = treaty.getProducerRef();
        List<ScoredCandidate> scored = producers.stream()
                .map(p -> new ScoredCandidate(p, similarity(ref, p.getName())))
                .filter(sc -> sc.score.compareTo(MIN_CANDIDATE_SCORE) >= 0)
                .sorted(Comparator.comparing(ScoredCandidate::score).reversed()
                        .thenComparing(sc -> sc.producer.getName() == null ? ""
                                : sc.producer.getName().toLowerCase()))
                .limit(TOP_K_CANDIDATES)
                .toList();

        progressService.recordProcessed(tenantId);
        if (scored.isEmpty()) {
            progressService.recordSkip(tenantId, treaty.getId());
            return Mono.empty();
        }

        ScoredCandidate top = scored.get(0);
        if (top.score.compareTo(AUTO_ACCEPT_THRESHOLD) >= 0) {
            return autoAccept(tenantId, treaty, top, actorId, actorEmail)
                    .then(persistPendingCandidates(tenantId, treaty,
                            scored.subList(1, scored.size())));
        }

        return persistPendingCandidates(tenantId, treaty, scored);
    }

    private Mono<Void> autoAccept(UUID tenantId, Treaty treaty, ScoredCandidate top,
                                   String actorId, String actorEmail) {
        treaty.setProducerId(top.producer.getId());
        return treatyRepository.save(treaty)
                .then(insertCandidate(treaty, top, STATUS_ACCEPTED, actorId, actorEmail))
                .doOnSuccess(v -> progressService.recordAutoAccepted(tenantId));
    }

    private Mono<Void> persistPendingCandidates(UUID tenantId, Treaty treaty,
                                                 List<ScoredCandidate> scored) {
        if (scored.isEmpty()) return Mono.empty();
        return Flux.fromIterable(scored)
                .concatMap(sc -> insertCandidate(treaty, sc, STATUS_PENDING, null, null)
                        .doOnSuccess(v -> progressService.recordPending(tenantId)))
                .then();
    }

    private Mono<Void> insertCandidate(Treaty treaty, ScoredCandidate sc, String status,
                                        String resolvedActorId, String resolvedActorEmail) {
        ProducerBackfillCandidate row = new ProducerBackfillCandidate();
        row.setTreatyId(treaty.getId());
        row.setTreatyProducerRef(treaty.getProducerRef());
        row.setCandidateProducerId(sc.producer.getId());
        row.setConfidenceScore(sc.score);
        row.setMatchStrategy(STRATEGY_LEVENSHTEIN);
        row.setStatus(status);
        row.setCreatedAt(OffsetDateTime.now());
        if (STATUS_ACCEPTED.equals(status)) {
            row.setResolvedAt(OffsetDateTime.now());
            row.setResolvedActorId(parseUuid(resolvedActorId));
            row.setResolvedActorEmail(resolvedActorEmail);
        }
        return candidateRepository.save(row)
                .then()
                .onErrorResume(DuplicateKeyException.class, e -> {
                    log.debug("Backfill candidate already present for treaty={} candidate={} — "
                            + "idempotent rerun", treaty.getId(), sc.producer.getId());
                    return Mono.empty();
                });
    }

    /**
     * Levenshtein-based similarity normalised to [0, 1]. Case-insensitive.
     * {@code similarity("", "") = 1}; identical strings score 1;
     * completely-different strings tend to 0. Trims whitespace on both sides
     * to shrug off stray padding in legacy {@code producer_ref} rows.
     */
    static BigDecimal similarity(String a, String b) {
        String left  = a == null ? "" : a.trim().toLowerCase();
        String right = b == null ? "" : b.trim().toLowerCase();
        int maxLen = Math.max(left.length(), right.length());
        if (maxLen == 0) return BigDecimal.ONE;
        int distance = levenshtein(left, right);
        return BigDecimal.ONE.subtract(
                BigDecimal.valueOf(distance).divide(
                        BigDecimal.valueOf(maxLen), 3, RoundingMode.HALF_UP));
    }

    /**
     * Classic two-row Levenshtein edit distance. O(m*n) time, O(min(m,n))
     * space. Comparable with strings up to a few hundred chars — {@code
     * producer_ref} and {@code producer.name} are both VARCHAR(120) / (200).
     */
    private static int levenshtein(String a, String b) {
        int m = a.length();
        int n = b.length();
        if (m == 0) return n;
        if (n == 0) return m;
        int[] prev = new int[n + 1];
        int[] curr = new int[n + 1];
        for (int j = 0; j <= n; j++) prev[j] = j;
        for (int i = 1; i <= m; i++) {
            curr[0] = i;
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= n; j++) {
                int cost = (ca == b.charAt(j - 1)) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = curr; curr = tmp;
        }
        return prev[n];
    }

    private static UUID parseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }

    /**
     * Pull the tenantId out of Reactor context and parse it as a UUID for the
     * progress-tracker key. Falls back to a stable zero-UUID when the context
     * is absent so the progress-tracker still records a run (unit-test path).
     */
    private static UUID tenantUuidFrom(reactor.util.context.ContextView ctx) {
        String raw = TenantContext.get(ctx);
        if (raw == null || raw.isBlank()) return new UUID(0L, 0L);
        try { return UUID.fromString(raw); } catch (IllegalArgumentException e) {
            return new UUID(0L, raw.hashCode());
        }
    }

    record ScoredCandidate(Producer producer, BigDecimal score) {}
}

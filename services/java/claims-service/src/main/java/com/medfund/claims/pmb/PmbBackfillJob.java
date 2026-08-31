package com.medfund.claims.pmb;

import com.medfund.claims.entity.Claim;
import com.medfund.claims.repository.ClaimRepository;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Batch job that re-runs {@link PmbClassifier} across the tenant's claim
 * corpus and writes the classification back to {@code claims.is_pmb} /
 * {@code claims.pmb_condition_code}. Kicked off manually after Phase 17's
 * {@code industry_default_v1} PMB rule seed lands (or after a tenant adds
 * custom PMB rules). Chunked keyset pagination keeps each batch bounded
 * even on schemes with millions of historical claims.
 *
 * <p>Idempotent: rows whose current DB state already matches the classifier
 * verdict are skipped (no DB write, no audit event). A second run therefore
 * produces zero updates unless the rule set changed.
 *
 * <p>Phase 16 §B REG7.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PmbBackfillJob {

    public static final int DEFAULT_CHUNK_SIZE = 1000;
    private static final UUID ZERO_UUID = new UUID(0L, 0L);

    private final ClaimRepository claimRepository;
    private final PmbClassifier classifier;
    private final AuditPublisher auditPublisher;

    /**
     * Result of one backfill run. {@code processed} counts every claim the job
     * inspected; {@code classified} counts rows whose classification changed
     * (either flipped in or out of PMB, or the condition-code moved).
     */
    public record BackfillResult(long processed, long classified) {}

    public Mono<BackfillResult> run(String tenantId, String actorId, String actorEmail) {
        return run(tenantId, actorId, actorEmail, DEFAULT_CHUNK_SIZE);
    }

    public Mono<BackfillResult> run(String tenantId, String actorId, String actorEmail, int chunkSize) {
        if (chunkSize <= 0) {
            return Mono.error(new IllegalArgumentException("chunkSize must be > 0"));
        }
        String correlationId = UUID.randomUUID().toString();
        log.info("[pmb-backfill] starting tenant={} actor={} correlation={} chunkSize={}",
                tenantId, actorId, correlationId, chunkSize);
        return processChunk(tenantId, actorId, actorEmail, ZERO_UUID, chunkSize, 0L, 0L, correlationId)
                .doOnSuccess(r -> log.info(
                        "[pmb-backfill] complete tenant={} correlation={} processed={} classified={}",
                        tenantId, correlationId, r.processed(), r.classified()));
    }

    private Mono<BackfillResult> processChunk(String tenantId, String actorId, String actorEmail,
                                               UUID afterId, int chunkSize,
                                               long processed, long classified, String correlationId) {
        return claimRepository.findChunkAfterId(afterId, chunkSize)
                .collectList()
                .flatMap(claims -> {
                    if (claims.isEmpty()) {
                        return Mono.just(new BackfillResult(processed, classified));
                    }
                    UUID lastId = claims.get(claims.size() - 1).getId();
                    return Flux.fromIterable(claims)
                            .concatMap(claim -> classifyAndPersist(tenantId, actorId, actorEmail, claim, correlationId))
                            .reduce(0L, Long::sum)
                            .flatMap(changed -> processChunk(tenantId, actorId, actorEmail,
                                    lastId, chunkSize,
                                    processed + claims.size(),
                                    classified + changed,
                                    correlationId));
                });
    }

    private Mono<Long> classifyAndPersist(String tenantId, String actorId, String actorEmail,
                                           Claim claim, String correlationId) {
        return classifier.classify(claim)
                .defaultIfEmpty(PmbClassification.NOT_PMB)
                .flatMap(verdict -> {
                    if (!classificationChanged(claim, verdict)) {
                        return Mono.just(0L);
                    }
                    Map<String, Object> oldValue = snapshot(claim);
                    claim.setIsPmb(verdict.isPmb());
                    claim.setPmbConditionCode(verdict.conditionCode());
                    Map<String, Object> newValue = snapshot(claim);
                    return claimRepository.save(claim)
                            .then(publishAudit(tenantId, actorId, actorEmail, claim,
                                    oldValue, newValue, correlationId))
                            .thenReturn(1L);
                })
                .onErrorResume(err -> {
                    log.warn("[pmb-backfill] classify failed tenant={} claim={} correlation={}: {}",
                            tenantId, claim.getId(), correlationId, err.getMessage());
                    return Mono.just(0L);
                });
    }

    private static boolean classificationChanged(Claim claim, PmbClassification verdict) {
        boolean currentPmb = Boolean.TRUE.equals(claim.getIsPmb());
        if (currentPmb != verdict.isPmb()) return true;
        return !Objects.equals(claim.getPmbConditionCode(), verdict.conditionCode());
    }

    private static Map<String, Object> snapshot(Claim claim) {
        Map<String, Object> m = new HashMap<>();
        m.put("isPmb", Boolean.TRUE.equals(claim.getIsPmb()));
        m.put("pmbConditionCode", claim.getPmbConditionCode());
        return m;
    }

    private Mono<Void> publishAudit(String tenantId, String actorId, String actorEmail,
                                     Claim claim, Map<String, Object> oldValue,
                                     Map<String, Object> newValue, String correlationId) {
        String claimId = claim.getId() != null ? claim.getId().toString() : "unknown";
        String claimNumber = claim.getClaimNumber() != null ? claim.getClaimNumber() : claimId;
        AuditEvent event = AuditEvent.create(
                tenantId,
                "claim.pmb_classification",
                claimId,
                "PMB backfill for claim " + claimNumber,
                "PMB_BACKFILL",
                actorId,
                actorEmail,
                oldValue,
                newValue,
                new String[]{"isPmb", "pmbConditionCode"},
                correlationId
        );
        return auditPublisher.publish(event);
    }
}

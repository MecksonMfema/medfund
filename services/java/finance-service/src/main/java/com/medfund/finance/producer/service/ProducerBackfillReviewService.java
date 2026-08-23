package com.medfund.finance.producer.service;

import com.medfund.finance.producer.dto.BackfillCandidateResponse;
import com.medfund.finance.producer.entity.ProducerBackfillCandidate;
import com.medfund.finance.producer.repository.ProducerBackfillCandidateRepository;
import com.medfund.finance.producer.repository.ProducerRepository;
import com.medfund.finance.reinsurance.entity.Treaty;
import com.medfund.finance.reinsurance.repository.TreatyRepository;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Review surface for {@link ProducerBackfillJob} output. Tenant admins page
 * through PENDING candidates and either accept (sets
 * {@code treaty.producer_id} + rejects sibling candidates) or reject (marks
 * the single candidate REJECTED without touching the treaty).
 *
 * <p>Both actions emit an {@link AuditEvent} against the
 * {@code ProducerBackfillCandidate} entity type; accept additionally emits a
 * BACKFILL_ACCEPTED event against the treaty so the treaty history reflects
 * where its {@code producer_id} came from.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProducerBackfillReviewService {

    private static final String CANDIDATE_ENTITY_TYPE = "ProducerBackfillCandidate";
    private static final String TREATY_ENTITY_TYPE = "Treaty";

    private final ProducerBackfillCandidateRepository candidateRepository;
    private final TreatyRepository treatyRepository;
    private final ProducerRepository producerRepository;
    private final AuditPublisher auditPublisher;

    public Flux<BackfillCandidateResponse> listPending(int page, int size) {
        int offset = Math.max(0, page) * Math.max(1, size);
        return candidateRepository
                .findByStatusOrderByConfidenceScoreDesc(
                        ProducerBackfillJob.STATUS_PENDING, offset, Math.max(1, size))
                .concatMap(this::enrich);
    }

    public Mono<Long> countPending() {
        return candidateRepository.countByStatus(ProducerBackfillJob.STATUS_PENDING);
    }

    /**
     * Accept a candidate: sets {@code treaty.producer_id = candidate.candidate_producer_id};
     * flips the candidate to ACCEPTED; all OTHER pending candidates for the
     * same treaty flip to REJECTED. Two AuditEvents fire — one on the
     * candidate (ACCEPT) and one on the treaty (BACKFILL_ACCEPTED).
     */
    @Transactional
    public Mono<Void> accept(UUID candidateId, String actorId, String actorEmail) {
        return candidateRepository.findById(candidateId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException(
                        "Backfill candidate not found: " + candidateId)))
                .flatMap(candidate -> {
                    if (!ProducerBackfillJob.STATUS_PENDING.equals(candidate.getStatus())) {
                        return Mono.error(new IllegalStateException(
                                "Candidate already resolved: " + candidate.getStatus()));
                    }
                    return treatyRepository.findById(candidate.getTreatyId())
                            .switchIfEmpty(Mono.error(new IllegalStateException(
                                    "Treaty missing for candidate " + candidate.getId())))
                            .flatMap(treaty -> updateTreaty(treaty, candidate, actorId, actorEmail))
                            .then(Mono.defer(() -> rejectSiblings(candidate, actorId, actorEmail)))
                            .then(Mono.defer(() -> finalizeCandidate(candidate,
                                    ProducerBackfillJob.STATUS_ACCEPTED, actorId, actorEmail)));
                });
    }

    /**
     * Reject a single candidate. Marks the row REJECTED with the reviewer
     * stamped in; no other rows change. Treaty is untouched — a subsequent
     * candidate for the same treaty can still be accepted.
     */
    @Transactional
    public Mono<Void> reject(UUID candidateId, String actorId, String actorEmail) {
        return candidateRepository.findById(candidateId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException(
                        "Backfill candidate not found: " + candidateId)))
                .flatMap(candidate -> {
                    if (!ProducerBackfillJob.STATUS_PENDING.equals(candidate.getStatus())) {
                        return Mono.error(new IllegalStateException(
                                "Candidate already resolved: " + candidate.getStatus()));
                    }
                    return finalizeCandidate(candidate, "REJECTED", actorId, actorEmail);
                });
    }

    private Mono<Treaty> updateTreaty(Treaty treaty, ProducerBackfillCandidate candidate,
                                       String actorId, String actorEmail) {
        Map<String, Object> before = treatySnapshot(treaty);
        treaty.setProducerId(candidate.getCandidateProducerId());
        return treatyRepository.save(treaty)
                .flatMap(saved -> publishTreatyAudit(saved, before, treatySnapshot(saved),
                        actorId, actorEmail).thenReturn(saved));
    }

    private Mono<Void> rejectSiblings(ProducerBackfillCandidate accepted,
                                       String actorId, String actorEmail) {
        return candidateRepository
                .findSiblingsPendingFor(accepted.getTreatyId(), accepted.getId())
                .concatMap(sibling -> finalizeCandidate(sibling, "REJECTED", actorId, actorEmail))
                .then();
    }

    private Mono<Void> finalizeCandidate(ProducerBackfillCandidate candidate, String terminalStatus,
                                          String actorId, String actorEmail) {
        Map<String, Object> before = candidateSnapshot(candidate);
        candidate.setStatus(terminalStatus);
        candidate.setResolvedAt(OffsetDateTime.now());
        candidate.setResolvedActorId(parseUuid(actorId));
        candidate.setResolvedActorEmail(actorEmail);
        return candidateRepository.save(candidate)
                .flatMap(saved -> publishCandidateAudit(terminalStatus, saved, before,
                        candidateSnapshot(saved), actorId, actorEmail));
    }

    private Mono<BackfillCandidateResponse> enrich(ProducerBackfillCandidate c) {
        Mono<String> treatyRefMono = treatyRepository.findById(c.getTreatyId())
                .map(Treaty::getTreatyRef)
                .defaultIfEmpty("(deleted treaty)");
        Mono<String[]> producerFields = c.getCandidateProducerId() == null
                ? Mono.just(new String[]{null, "(deleted producer)"})
                : producerRepository.findById(c.getCandidateProducerId())
                        .map(p -> new String[]{p.getProducerCode(), p.getName()})
                        .defaultIfEmpty(new String[]{null, "(deleted producer)"});
        return Mono.zip(treatyRefMono, producerFields)
                .map(t -> BackfillCandidateResponse.from(c, t.getT1(), t.getT2()[0], t.getT2()[1]));
    }

    private Map<String, Object> candidateSnapshot(ProducerBackfillCandidate c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("treatyId",             c.getTreatyId() == null ? null : c.getTreatyId().toString());
        m.put("treatyProducerRef",    c.getTreatyProducerRef());
        m.put("candidateProducerId",  c.getCandidateProducerId() == null ? null : c.getCandidateProducerId().toString());
        m.put("confidenceScore",      c.getConfidenceScore());
        m.put("matchStrategy",        c.getMatchStrategy());
        m.put("status",               c.getStatus());
        return m;
    }

    private Map<String, Object> treatySnapshot(Treaty t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("treatyRef",   t.getTreatyRef());
        m.put("producerRef", t.getProducerRef());
        m.put("producerId",  t.getProducerId() == null ? null : t.getProducerId().toString());
        return m;
    }

    private String[] diff(Map<String, Object> before, Map<String, Object> after) {
        if (before == null || after == null) return new String[0];
        return before.keySet().stream()
                .filter(k -> !java.util.Objects.equals(before.get(k), after.get(k)))
                .toArray(String[]::new);
    }

    private Mono<Void> publishCandidateAudit(String terminalStatus, ProducerBackfillCandidate saved,
                                              Map<String, Object> before, Map<String, Object> after,
                                              String actorId, String actorEmail) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            String action = ProducerBackfillJob.STATUS_ACCEPTED.equals(terminalStatus) ? "ACCEPT" : "REJECT";
            String entityName = "candidate " + saved.getTreatyProducerRef() + " → producer "
                    + saved.getCandidateProducerId();
            var event = AuditEvent.create(
                    tenantId != null ? tenantId : "unknown",
                    CANDIDATE_ENTITY_TYPE,
                    saved.getId().toString(),
                    entityName,
                    action,
                    actorId != null ? actorId : "system",
                    actorEmail,
                    before, after,
                    diff(before, after),
                    UUID.randomUUID().toString());
            return auditPublisher.publish(event);
        });
    }

    private Mono<Void> publishTreatyAudit(Treaty saved,
                                           Map<String, Object> before, Map<String, Object> after,
                                           String actorId, String actorEmail) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            var event = AuditEvent.create(
                    tenantId != null ? tenantId : "unknown",
                    TREATY_ENTITY_TYPE,
                    saved.getId().toString(),
                    saved.getTreatyRef(),
                    "BACKFILL_ACCEPTED",
                    actorId != null ? actorId : "system",
                    actorEmail,
                    before, after,
                    diff(before, after),
                    UUID.randomUUID().toString());
            return auditPublisher.publish(event);
        });
    }

    private static UUID parseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }
}

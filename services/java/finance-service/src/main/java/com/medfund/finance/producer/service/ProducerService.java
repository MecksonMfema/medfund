package com.medfund.finance.producer.service;

import com.medfund.finance.dto.PageResponse;
import com.medfund.finance.producer.dto.CreateProducerRequest;
import com.medfund.finance.producer.dto.ProducerResponse;
import com.medfund.finance.producer.dto.UpdateProducerRequest;
import com.medfund.finance.producer.entity.MemberProducerAssignment;
import com.medfund.finance.producer.entity.Producer;
import com.medfund.finance.producer.repository.MemberProducerAssignmentRepository;
import com.medfund.finance.producer.repository.ProducerRepository;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * CRUD for producer / broker counterparties. Deactivation is a soft flip on
 * {@code is_active}; the {@code producer_code} column is globally unique per
 * tenant (V092 UNIQUE constraint). Cycles in the {@code parentProducerId}
 * hierarchy are rejected pre-save by walking ancestry.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProducerService {

    private static final String ENTITY_TYPE = "Producer";
    private static final String ASSIGNMENT_ENTITY_TYPE = "MemberProducerAssignment";

    private final ProducerRepository repository;
    private final MemberProducerAssignmentRepository assignmentRepository;
    private final AuditPublisher auditPublisher;

    public Mono<PageResponse<ProducerResponse>> list(int page, int size, Boolean active) {
        int offset = page * size;
        var contentFlux = (active != null
                ? repository.findPageByActive(active, offset, size)
                : repository.findPage(offset, size))
                .map(ProducerResponse::from)
                .collectList();
        var countMono = (active != null ? repository.countByActive(active) : repository.countAll());
        return contentFlux.zipWith(countMono, (rows, total) -> PageResponse.of(rows, total, page, size));
    }

    public Mono<ProducerResponse> get(UUID id) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Producer not found: " + id)))
                .map(ProducerResponse::from);
    }

    public Flux<ProducerResponse> ancestry(UUID id) {
        return repository.findAncestryOf(id).map(ProducerResponse::from);
    }

    public Flux<ProducerResponse> children(UUID parentId) {
        return repository.findChildren(parentId).map(ProducerResponse::from);
    }

    public Flux<ProducerResponse> search(String q, int limit) {
        String needle = q == null ? "" : q.trim();
        if (needle.isEmpty()) return Flux.empty();
        int capped = Math.max(1, Math.min(limit, 100));
        return repository.search(needle, capped).map(ProducerResponse::from);
    }

    @Transactional
    public Mono<ProducerResponse> create(CreateProducerRequest req, String actorId, String actorEmail) {
        return repository.findByProducerCode(req.producerCode())
                .flatMap(existing -> Mono.<Producer>error(new IllegalStateException(
                        "Producer code already in use: " + req.producerCode())))
                .switchIfEmpty(Mono.defer(() -> validateParent(req.parentProducerId(), null)
                        .then(Mono.defer(() -> {
                            Producer p = new Producer();
                            p.setProducerCode(req.producerCode());
                            p.setName(req.name());
                            p.setContactEmail(req.contactEmail());
                            p.setContactPhone(req.contactPhone());
                            p.setJurisdictionCode(req.jurisdictionCode());
                            p.setHomeCurrency(req.homeCurrency());
                            p.setParentProducerId(req.parentProducerId());
                            p.setWhtPctOverride(req.whtPctOverride());
                            p.setBankingDetailsJson(req.bankingDetailsJson());
                            p.setActive(true);
                            OffsetDateTime now = OffsetDateTime.now();
                            p.setActivatedAt(now);
                            p.setCreatedAt(now);
                            p.setUpdatedAt(now);
                            p.setActorId(parseUuid(actorId));
                            p.setActorEmail(actorEmail);
                            return repository.save(p);
                        }))))
                .flatMap(saved -> publishAudit("CREATE", saved, null, snapshot(saved), actorId, actorEmail)
                        .thenReturn(ProducerResponse.from(saved)));
    }

    @Transactional
    public Mono<ProducerResponse> update(UUID id, UpdateProducerRequest req, String actorId, String actorEmail) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Producer not found: " + id)))
                .flatMap(existing -> validateParent(req.parentProducerId(), id)
                        .thenReturn(existing))
                .flatMap(existing -> {
                    Map<String, Object> before = snapshot(existing);
                    existing.setName(req.name());
                    existing.setContactEmail(req.contactEmail());
                    existing.setContactPhone(req.contactPhone());
                    existing.setJurisdictionCode(req.jurisdictionCode());
                    existing.setHomeCurrency(req.homeCurrency());
                    existing.setParentProducerId(req.parentProducerId());
                    existing.setWhtPctOverride(req.whtPctOverride());
                    existing.setBankingDetailsJson(req.bankingDetailsJson());
                    existing.setActive(req.active());
                    existing.setUpdatedAt(OffsetDateTime.now());
                    existing.setActorId(parseUuid(actorId));
                    existing.setActorEmail(actorEmail);
                    return repository.save(existing)
                            .flatMap(saved -> publishAudit("UPDATE", saved, before, snapshot(saved), actorId, actorEmail)
                                    .thenReturn(ProducerResponse.from(saved)));
                });
    }

    /**
     * Terminate a producer. Flips {@code is_active=false} + stamps
     * {@code terminated_at}, then closes every open {@code member_producer_assignment}
     * for this producer with {@code effective_to = last-day-of-month} of the
     * supplied {@code effectiveDate} (per {@code feedback_effective_date_snap}).
     * No auto-successor — commission calc during the resulting gap warns and
     * skips. One TERMINATE audit event for the producer + one CLOSE per
     * closed assignment. Idempotent: attempting to terminate an already-inactive
     * producer errors with 409 semantics via {@link IllegalStateException}.
     */
    @Transactional
    public Mono<ProducerResponse> terminate(UUID id, LocalDate effectiveDate,
                                            String actorId, String actorEmail) {
        LocalDate snapped = (effectiveDate != null ? effectiveDate : LocalDate.now())
                .with(TemporalAdjusters.lastDayOfMonth());
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Producer not found: " + id)))
                .flatMap(existing -> {
                    if (Boolean.FALSE.equals(existing.getActive())) {
                        return Mono.error(new IllegalStateException(
                                "Producer already terminated: " + existing.getProducerCode()));
                    }
                    Map<String, Object> before = snapshot(existing);
                    existing.setActive(false);
                    existing.setTerminatedAt(OffsetDateTime.now());
                    existing.setUpdatedAt(OffsetDateTime.now());
                    existing.setActorId(parseUuid(actorId));
                    existing.setActorEmail(actorEmail);
                    return repository.save(existing)
                            .flatMap(saved -> closeAllOpenAssignments(id, snapped, actorId, actorEmail)
                                    .then(publishAudit("TERMINATE", saved, before, snapshot(saved),
                                            actorId, actorEmail))
                                    .thenReturn(ProducerResponse.from(saved)));
                });
    }

    /**
     * Close every open assignment for a producer with {@code effective_to = snapped}.
     * Each row is saved individually and audited. The partial UNIQUE index
     * {@code ux_mpa_one_open_per_member} makes duplicate closures impossible
     * on rerun (the row's {@code effective_to} is no longer NULL).
     */
    private Mono<Void> closeAllOpenAssignments(UUID producerId, LocalDate snapped,
                                                String actorId, String actorEmail) {
        return assignmentRepository.findOpenByProducer(producerId)
                .concatMap(mpa -> {
                    LocalDate closeAt = snapped.isBefore(mpa.getEffectiveFrom())
                            ? mpa.getEffectiveFrom()
                            : snapped;
                    Map<String, Object> before = assignmentSnapshot(mpa);
                    mpa.setEffectiveTo(closeAt);
                    return assignmentRepository.save(mpa)
                            .flatMap(saved -> publishAssignmentAudit(saved, before,
                                    assignmentSnapshot(saved), actorId, actorEmail));
                })
                .then();
    }

    /**
     * Reject a parent assignment that would introduce a cycle. Walks the
     * proposed parent's ancestry; if it includes {@code selfId} the parent
     * is a descendant and would form a loop.
     */
    private Mono<Void> validateParent(UUID parentId, UUID selfId) {
        if (parentId == null) return Mono.empty();
        if (selfId != null && parentId.equals(selfId)) {
            return Mono.error(new IllegalArgumentException("A producer cannot be its own parent"));
        }
        return repository.findAncestryOf(parentId)
                .filter(anc -> selfId != null && anc.getId().equals(selfId))
                .hasElements()
                .flatMap(cycle -> Boolean.TRUE.equals(cycle)
                        ? Mono.error(new IllegalArgumentException(
                                "Reparenting would create a cycle in the producer hierarchy"))
                        : Mono.empty());
    }

    private Map<String, Object> snapshot(Producer p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("producerCode",       p.getProducerCode());
        m.put("name",               p.getName());
        m.put("contactEmail",       p.getContactEmail());
        m.put("contactPhone",       p.getContactPhone());
        m.put("jurisdictionCode",   p.getJurisdictionCode());
        m.put("homeCurrency",       p.getHomeCurrency());
        m.put("parentProducerId",   p.getParentProducerId() == null ? null : p.getParentProducerId().toString());
        m.put("whtPctOverride",     p.getWhtPctOverride());
        m.put("active",             p.getActive());
        return m;
    }

    private String[] diff(Map<String, Object> before, Map<String, Object> after) {
        if (before == null || after == null) return new String[0];
        return before.keySet().stream()
                .filter(k -> !Objects.equals(before.get(k), after.get(k)))
                .toArray(String[]::new);
    }

    private Mono<Void> publishAudit(String action, Producer entity,
                                    Map<String, Object> before, Map<String, Object> after,
                                    String actorId, String actorEmail) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            var event = AuditEvent.create(
                    tenantId != null ? tenantId : "unknown",
                    ENTITY_TYPE,
                    entity.getId().toString(),
                    entity.getProducerCode(),
                    action,
                    actorId != null ? actorId : "system",
                    actorEmail,
                    before,
                    after,
                    diff(before, after),
                    UUID.randomUUID().toString());
            return auditPublisher.publish(event);
        });
    }

    private UUID parseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }

    private Map<String, Object> assignmentSnapshot(MemberProducerAssignment m) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("memberId",      m.getMemberId() == null ? null : m.getMemberId().toString());
        map.put("producerId",    m.getProducerId() == null ? null : m.getProducerId().toString());
        map.put("effectiveFrom", m.getEffectiveFrom());
        map.put("effectiveTo",   m.getEffectiveTo());
        map.put("changeReason",  m.getChangeReason());
        return map;
    }

    private Mono<Void> publishAssignmentAudit(MemberProducerAssignment m,
                                              Map<String, Object> before, Map<String, Object> after,
                                              String actorId, String actorEmail) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            String entityName = "member " + m.getMemberId() + " → producer " + m.getProducerId();
            var event = AuditEvent.create(
                    tenantId != null ? tenantId : "unknown",
                    ASSIGNMENT_ENTITY_TYPE,
                    m.getId().toString(),
                    entityName,
                    "CLOSE",
                    actorId != null ? actorId : "system",
                    actorEmail,
                    before, after,
                    diff(before, after),
                    UUID.randomUUID().toString());
            return auditPublisher.publish(event);
        });
    }
}

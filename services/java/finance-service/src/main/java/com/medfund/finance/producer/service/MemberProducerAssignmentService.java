package com.medfund.finance.producer.service;

import com.medfund.finance.producer.dto.AssignMemberRequest;
import com.medfund.finance.producer.dto.AssignmentResponse;
import com.medfund.finance.producer.entity.MemberProducerAssignment;
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
import java.util.UUID;

/**
 * Manages the time-slice member ↔ producer assignment. Rules:
 * <ul>
 *   <li>{@code effective_from} snaps to 1st-of-month per {@code feedback_effective_date_snap}.</li>
 *   <li>{@code effective_to} on a manual close snaps to last-day-of-month.</li>
 *   <li>At most one open row per member: app-layer guard + partial UNIQUE
 *       index {@code ux_mpa_one_open_per_member}. Both are defence-in-depth.</li>
 *   <li>Reassign is transactional — closes the prior row and inserts a new
 *       one atomically; either both succeed or neither does.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberProducerAssignmentService {

    private static final String ENTITY_TYPE = "MemberProducerAssignment";

    private final MemberProducerAssignmentRepository assignmentRepository;
    private final ProducerRepository producerRepository;
    private final AuditPublisher auditPublisher;

    public Flux<AssignmentResponse> historyFor(UUID memberId) {
        return assignmentRepository.findHistoryFor(memberId).map(AssignmentResponse::from);
    }

    public Mono<AssignmentResponse> currentFor(UUID memberId) {
        return assignmentRepository.findOpenByMember(memberId).map(AssignmentResponse::from);
    }

    public Flux<AssignmentResponse> listOpenForProducer(UUID producerId, int page, int size) {
        int offset = Math.max(0, page) * Math.max(1, size);
        return assignmentRepository.findOpenByProducerPage(producerId, offset, size)
                .map(AssignmentResponse::from);
    }

    public Mono<Long> countOpenForProducer(UUID producerId) {
        return assignmentRepository.countOpenByProducer(producerId);
    }

    @Transactional
    public Mono<AssignmentResponse> assign(UUID memberId, AssignMemberRequest req,
                                           String actorId, String actorEmail) {
        if (req.effectiveFrom() == null) {
            return Mono.error(new IllegalArgumentException("effectiveFrom is required"));
        }
        LocalDate snapped = req.effectiveFrom().withDayOfMonth(1);
        return producerRepository.findById(req.producerId())
                .switchIfEmpty(Mono.error(new IllegalArgumentException(
                        "Producer not found: " + req.producerId())))
                .flatMap(p -> {
                    if (Boolean.FALSE.equals(p.getActive())) {
                        return Mono.error(new IllegalArgumentException(
                                "Cannot assign to a deactivated producer: " + req.producerId()));
                    }
                    return closePriorIfAny(memberId, snapped, actorId, actorEmail)
                            .then(Mono.defer(() -> insertOpen(memberId, req.producerId(), snapped,
                                    req.changeReason(), actorId, actorEmail)));
                });
    }

    /**
     * Close the currently-open assignment for the member, if any. The prior
     * row's {@code effective_to} is set to the day before the new
     * {@code effective_from} (never before the prior's own {@code effective_from}).
     */
    private Mono<Void> closePriorIfAny(UUID memberId, LocalDate newFrom,
                                       String actorId, String actorEmail) {
        return assignmentRepository.findOpenByMember(memberId)
                .flatMap(open -> {
                    LocalDate closeAt = newFrom.minusDays(1);
                    if (closeAt.isBefore(open.getEffectiveFrom())) {
                        return Mono.error(new IllegalArgumentException(
                                "New effective_from precedes existing open assignment start"));
                    }
                    Map<String, Object> before = snapshot(open);
                    open.setEffectiveTo(closeAt);
                    return assignmentRepository.save(open)
                            .flatMap(saved -> publishAudit("CLOSE", saved, before, snapshot(saved),
                                    actorId, actorEmail));
                })
                .then();
    }

    private Mono<AssignmentResponse> insertOpen(UUID memberId, UUID producerId, LocalDate from,
                                                String reason, String actorId, String actorEmail) {
        MemberProducerAssignment mpa = new MemberProducerAssignment();
        mpa.setMemberId(memberId);
        mpa.setProducerId(producerId);
        mpa.setEffectiveFrom(from);
        mpa.setChangeReason(reason);
        mpa.setCreatedAt(OffsetDateTime.now());
        mpa.setActorId(parseUuid(actorId));
        mpa.setActorEmail(actorEmail);
        return assignmentRepository.save(mpa)
                .flatMap(saved -> publishAudit("CREATE", saved, null, snapshot(saved), actorId, actorEmail)
                        .thenReturn(AssignmentResponse.from(saved)));
    }

    /**
     * Manually close the currently-open assignment for the member with no
     * successor. Commission calc during the gap warns + skips.
     * {@code effective_to} snaps to last-day-of-month.
     */
    @Transactional
    public Mono<Void> closeCurrent(UUID memberId, String actorId, String actorEmail) {
        LocalDate lastDay = LocalDate.now().with(TemporalAdjusters.lastDayOfMonth());
        return assignmentRepository.findOpenByMember(memberId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException(
                        "No open assignment for member: " + memberId)))
                .flatMap(open -> {
                    if (lastDay.isBefore(open.getEffectiveFrom())) {
                        return Mono.error(new IllegalArgumentException(
                                "Snapped close date precedes assignment start"));
                    }
                    Map<String, Object> before = snapshot(open);
                    open.setEffectiveTo(lastDay);
                    return assignmentRepository.save(open)
                            .flatMap(saved -> publishAudit("CLOSE", saved, before, snapshot(saved),
                                    actorId, actorEmail));
                })
                .then();
    }

    private Map<String, Object> snapshot(MemberProducerAssignment m) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("memberId",      m.getMemberId() == null ? null : m.getMemberId().toString());
        map.put("producerId",    m.getProducerId() == null ? null : m.getProducerId().toString());
        map.put("effectiveFrom", m.getEffectiveFrom());
        map.put("effectiveTo",   m.getEffectiveTo());
        map.put("changeReason",  m.getChangeReason());
        return map;
    }

    private Mono<Void> publishAudit(String action, MemberProducerAssignment m,
                                    Map<String, Object> before, Map<String, Object> after,
                                    String actorId, String actorEmail) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            String entityName = "member " + m.getMemberId() + " → producer " + m.getProducerId();
            var event = AuditEvent.create(
                    tenantId != null ? tenantId : "unknown",
                    ENTITY_TYPE,
                    m.getId().toString(),
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

    private String[] diff(Map<String, Object> before, Map<String, Object> after) {
        if (before == null || after == null) return new String[0];
        return before.keySet().stream()
                .filter(k -> !java.util.Objects.equals(before.get(k), after.get(k)))
                .toArray(String[]::new);
    }

    private UUID parseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }
}

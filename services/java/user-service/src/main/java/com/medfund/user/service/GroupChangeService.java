package com.medfund.user.service;

import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import com.medfund.user.dto.GroupChangeRequest;
import com.medfund.user.entity.PendingGroupChange;
import com.medfund.user.repository.GroupRepository;
import com.medfund.user.repository.MemberRepository;
import com.medfund.user.repository.PendingGroupChangeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * Request/approve/apply state machine for a group change. Mirrors
 * {@code SchemeChangeService} so both operations share an idiom.
 *
 * <p>Back-dated requests (effective_date &lt; current month) bypass
 * the state machine — the flip is applied immediately and a
 * {@code medfund.users.member-changed} event fires with
 * {@code backdated=true} so {@code GroupChangedConsumer} can post
 * the arrears / rebate on the ledger.
 *
 * <p>Forward-dated (or current-month) requests go through the normal
 * PENDING → APPROVED → APPLIED flow; the scheduled executor calls
 * {@link #apply} on the effective date.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroupChangeService {

    private final PendingGroupChangeRepository repository;
    private final MemberRepository memberRepository;
    private final GroupRepository groupRepository;
    private final AuditPublisher auditPublisher;
    private final UserEventPublisher eventPublisher;

    public Flux<PendingGroupChange> findByMemberId(UUID memberId) {
        return repository.findByMemberId(memberId);
    }

    public Flux<PendingGroupChange> findPending() {
        return repository.findByStatus("PENDING");
    }

    public Flux<PendingGroupChange> findReadyToApply(LocalDate today) {
        return repository.findReadyToApply(today);
    }

    /**
     * Book a group change. If the effective date is in the past the
     * flip is applied immediately (no PENDING row) and a
     * back-dated MEMBER_CHANGED event fires. Otherwise the request
     * is stashed as PENDING for operator approval.
     */
    @Transactional
    public Mono<PendingGroupChange> request(UUID memberId, GroupChangeRequest req,
                                             String actorId, String actorEmail) {
        LocalDate effective = req.effectiveDateOrDefault();
        return validateAndBuild(memberId, req, effective, actorId)
                .flatMap(row -> {
                    // Back-dated? Fast-path — skip PENDING, flip the group,
                    // publish a back-dated event so the contributions
                    // consumer posts arrears/rebate.
                    if (effective.isBefore(currentMonth())) {
                        return applyImmediately(row, actorId, actorEmail);
                    }
                    // Forward-dated → stash as PENDING; ScheduledChangesExecutor
                    // will trigger apply() on the effective date after approval.
                    return persistPending(row, actorId, actorEmail);
                });
    }

    @Transactional
    public Mono<PendingGroupChange> approve(UUID id, String actorId, String actorEmail) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Group change not found: " + id)))
                .flatMap(pc -> {
                    if (!"PENDING".equals(pc.getStatus())) {
                        return Mono.error(new IllegalStateException(
                                "Group change " + id + " is not PENDING, current: " + pc.getStatus()));
                    }
                    String previous = pc.getStatus();
                    pc.setStatus("APPROVED");
                    pc.setApprovedBy(safeUuid(actorId));
                    pc.setApprovedAt(Instant.now());
                    pc.setUpdatedAt(Instant.now());
                    return repository.save(pc)
                            .flatMap(saved -> publishAudit(saved, "UPDATE", actorId, actorEmail,
                                    Map.of("status", previous),
                                    Map.of("status", saved.getStatus(),
                                           "approvedBy", actorId))
                                    .thenReturn(saved));
                });
    }

    @Transactional
    public Mono<PendingGroupChange> reject(UUID id, String reason, String actorId, String actorEmail) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Group change not found: " + id)))
                .flatMap(pc -> {
                    if (!"PENDING".equals(pc.getStatus()) && !"APPROVED".equals(pc.getStatus())) {
                        return Mono.error(new IllegalStateException(
                                "Group change " + id + " is not PENDING/APPROVED, current: " + pc.getStatus()));
                    }
                    String previous = pc.getStatus();
                    pc.setStatus("REJECTED");
                    pc.setRejectionReason(reason);
                    pc.setUpdatedAt(Instant.now());
                    return repository.save(pc)
                            .flatMap(saved -> publishAudit(saved, "UPDATE", actorId, actorEmail,
                                    Map.of("status", previous),
                                    Map.of("status", saved.getStatus(),
                                           "rejectionReason", reason == null ? "" : reason))
                                    .thenReturn(saved));
                });
    }

    /**
     * Called by ScheduledChangesExecutor on the effective date, or
     * inline from {@link #request} for back-dated requests. Flips
     * {@code members.group_id} and marks the row APPLIED.
     */
    @Transactional
    public Mono<PendingGroupChange> apply(UUID id, String actorId, String actorEmail) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Group change not found: " + id)))
                .flatMap(pc -> {
                    if (!"APPROVED".equals(pc.getStatus())) {
                        return Mono.error(new IllegalStateException(
                                "Group change " + id + " is not APPROVED, current: " + pc.getStatus()));
                    }
                    return flipMemberGroup(pc)
                            .then(Mono.defer(() -> {
                                pc.setStatus("APPLIED");
                                pc.setAppliedAt(Instant.now());
                                pc.setUpdatedAt(Instant.now());
                                return repository.save(pc);
                            }))
                            .flatMap(saved -> publishAudit(saved, "UPDATE", actorId, actorEmail,
                                    Map.of("status", "APPROVED"),
                                    Map.of("status", saved.getStatus(),
                                           "groupId", saved.getToGroupId().toString()))
                                    .then(publishMemberChanged(saved, actorId, actorEmail))
                                    .thenReturn(saved));
                });
    }

    // ── Private helpers ──────────────────────────────────────────────

    private Mono<PendingGroupChange> validateAndBuild(UUID memberId, GroupChangeRequest req,
                                                      LocalDate effective,
                                                      String actorId) {
        return memberRepository.findById(memberId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Member not found: " + memberId)))
                .flatMap(member -> {
                    if (member.getGroupId() != null && member.getGroupId().equals(req.targetGroupId())) {
                        return Mono.error(new IllegalArgumentException(
                                "Target group is the same as current group"));
                    }
                    return groupRepository.findById(req.targetGroupId())
                            .switchIfEmpty(Mono.error(new IllegalArgumentException(
                                    "Target group not found: " + req.targetGroupId())))
                            .flatMap(g -> {
                                if (!"active".equalsIgnoreCase(g.getStatus())) {
                                    return Mono.<PendingGroupChange>error(new IllegalArgumentException(
                                            "Target group is not active, status: " + g.getStatus()));
                                }
                                return repository.countLiveByMemberId(memberId)
                                        .defaultIfEmpty(0L)
                                        .flatMap(live -> {
                                            if (live != null && live > 0L) {
                                                return Mono.<PendingGroupChange>error(new IllegalStateException(
                                                        "Member already has a live group change request"));
                                            }
                                            PendingGroupChange row = new PendingGroupChange();
                                            row.setMemberId(memberId);
                                            row.setFromGroupId(member.getGroupId());
                                            row.setToGroupId(req.targetGroupId());
                                            row.setRequestedDate(LocalDate.now());
                                            row.setEffectiveDate(effective);
                                            row.setReason(req.reason());
                                            row.setCreatedAt(Instant.now());
                                            row.setUpdatedAt(Instant.now());
                                            row.setCreatedBy(safeUuid(actorId));
                                            return Mono.just(row);
                                        });
                            });
                });
    }

    private Mono<PendingGroupChange> persistPending(PendingGroupChange row,
                                                     String actorId, String actorEmail) {
        row.setStatus("PENDING");
        return repository.save(row)
                .flatMap(saved -> publishAudit(saved, "CREATE", actorId, actorEmail, null,
                        Map.of("memberId", saved.getMemberId().toString(),
                               "toGroupId", saved.getToGroupId().toString(),
                               "effectiveDate", saved.getEffectiveDate().toString(),
                               "status", saved.getStatus()))
                        .thenReturn(saved));
    }

    private Mono<PendingGroupChange> applyImmediately(PendingGroupChange row,
                                                       String actorId, String actorEmail) {
        row.setStatus("APPLIED");
        row.setApprovedBy(safeUuid(actorId));
        row.setApprovedAt(Instant.now());
        row.setAppliedAt(Instant.now());
        return flipMemberGroup(row)
                .then(repository.save(row))
                .flatMap(saved -> publishAudit(saved, "CREATE", actorId, actorEmail, null,
                        Map.of("memberId", saved.getMemberId().toString(),
                               "toGroupId", saved.getToGroupId().toString(),
                               "effectiveDate", saved.getEffectiveDate().toString(),
                               "status", saved.getStatus(),
                               "backdated", "true"))
                        .then(publishMemberChanged(saved, actorId, actorEmail))
                        .thenReturn(saved));
    }

    private Mono<Void> flipMemberGroup(PendingGroupChange row) {
        return memberRepository.findById(row.getMemberId())
                .switchIfEmpty(Mono.error(new IllegalStateException(
                        "Member vanished mid-change: " + row.getMemberId())))
                .flatMap(m -> {
                    m.setGroupId(row.getToGroupId());
                    m.setUpdatedAt(Instant.now());
                    return memberRepository.save(m);
                })
                .then();
    }

    private Mono<Void> publishMemberChanged(PendingGroupChange saved,
                                             String actorId, String actorEmail) {
        boolean backdated = saved.getEffectiveDate() != null
                && saved.getEffectiveDate().isBefore(currentMonth());
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            return eventPublisher.publishMemberChanged(
                    tenantId,
                    saved.getMemberId().toString(),
                    "GROUP_CHANGE",
                    saved.getFromGroupId() != null ? saved.getFromGroupId().toString() : null,
                    saved.getToGroupId().toString(),
                    saved.getEffectiveDate() != null ? saved.getEffectiveDate().toString() : null,
                    backdated,
                    actorId, actorEmail);
        });
    }

    private Mono<Void> publishAudit(PendingGroupChange saved, String action,
                                     String actorId, String actorEmail,
                                     Map<String, Object> oldValue, Map<String, Object> newValue) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            String name = "member " + saved.getMemberId() + " "
                    + (saved.getFromGroupId() != null ? saved.getFromGroupId() : "?")
                    + " -> " + saved.getToGroupId();
            AuditEvent event = AuditEvent.create(
                    tenantId != null ? tenantId : "unknown",
                    "GroupChange",
                    saved.getId().toString(),
                    name,
                    action,
                    actorId,
                    actorEmail,
                    oldValue,
                    newValue,
                    new String[]{"status"},
                    UUID.randomUUID().toString());
            return auditPublisher.publish(event);
        });
    }

    private static LocalDate currentMonth() {
        return LocalDate.now().withDayOfMonth(1);
    }

    private static UUID safeUuid(String s) {
        if (s == null) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }
}

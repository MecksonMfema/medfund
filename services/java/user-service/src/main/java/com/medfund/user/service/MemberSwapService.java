package com.medfund.user.service;

import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import com.medfund.user.dto.MemberSwapRequest;
import com.medfund.user.entity.Dependant;
import com.medfund.user.entity.Member;
import com.medfund.user.entity.MemberDependantSwap;
import com.medfund.user.repository.DependantRepository;
import com.medfund.user.repository.MemberDependantSwapRepository;
import com.medfund.user.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * Request/approve/apply state machine for a role-swap between a
 * member (the current principal) and one of their dependants. The
 * apply step is atomic: the dependant is promoted into
 * {@code members}, the old member is demoted into {@code dependants}
 * under the new principal, sibling dependants re-parent onto the new
 * principal, and both old rows are marked {@code status='swapped'}
 * with the {@code swapped_to_id} pointer set so historical
 * claims/contributions can follow the redirect without rewriting the
 * FK.
 *
 * <p>Back-dated requests bypass PENDING and apply immediately.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberSwapService {

    private final MemberDependantSwapRepository swapRepository;
    private final MemberRepository memberRepository;
    private final DependantRepository dependantRepository;
    private final MemberNumberService memberNumberService;
    private final AuditPublisher auditPublisher;
    private final UserEventPublisher eventPublisher;
    private final DatabaseClient db;

    public Flux<MemberDependantSwap> findByMemberId(UUID memberId) {
        return swapRepository.findByOldMemberId(memberId);
    }

    public Flux<MemberDependantSwap> findReadyToApply(LocalDate today) {
        return swapRepository.findReadyToApply(today);
    }

    @Transactional
    public Mono<MemberDependantSwap> request(UUID memberId, MemberSwapRequest req,
                                              String actorId, String actorEmail) {
        LocalDate effective = req.effectiveDateOrDefault();
        return validate(memberId, req)
                // validate() completes empty on success; use then(defer)
                // rather than flatMap so the downstream Mono still fires.
                .then(Mono.defer(() -> swapRepository.countLive(memberId, req.dependantId())
                        .defaultIfEmpty(0L)
                        .flatMap(live -> {
                            if (live != null && live > 0L) {
                                return Mono.<MemberDependantSwap>error(new IllegalStateException(
                                        "A live swap request already exists for this pair"));
                            }
                            MemberDependantSwap row = new MemberDependantSwap();
                            row.setOldMemberId(memberId);
                            row.setDependantId(req.dependantId());
                            row.setRequestedDate(LocalDate.now());
                            row.setEffectiveDate(effective);
                            row.setReason(req.reason());
                            row.setCreatedAt(Instant.now());
                            row.setUpdatedAt(Instant.now());
                            row.setCreatedBy(safeUuid(actorId));
                            LocalDate currentMonth = LocalDate.now().withDayOfMonth(1);
                            if (effective.isBefore(currentMonth)) {
                                return applyImmediately(row, actorId, actorEmail);
                            }
                            return persistPending(row, actorId, actorEmail);
                        })));
    }

    @Transactional
    public Mono<MemberDependantSwap> approve(UUID id, String actorId, String actorEmail) {
        return swapRepository.findById(id)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Swap not found: " + id)))
                .flatMap(row -> {
                    if (!"PENDING".equals(row.getStatus())) {
                        return Mono.error(new IllegalStateException(
                                "Swap " + id + " is not PENDING, current: " + row.getStatus()));
                    }
                    String previous = row.getStatus();
                    row.setStatus("APPROVED");
                    row.setApprovedBy(safeUuid(actorId));
                    row.setApprovedAt(Instant.now());
                    row.setUpdatedAt(Instant.now());
                    return swapRepository.save(row)
                            .flatMap(saved -> publishAudit(saved, "UPDATE", actorId, actorEmail,
                                    Map.of("status", previous),
                                    Map.of("status", saved.getStatus(), "approvedBy", actorId))
                                    .thenReturn(saved));
                });
    }

    @Transactional
    public Mono<MemberDependantSwap> reject(UUID id, String reason,
                                             String actorId, String actorEmail) {
        return swapRepository.findById(id)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Swap not found: " + id)))
                .flatMap(row -> {
                    if (!"PENDING".equals(row.getStatus()) && !"APPROVED".equals(row.getStatus())) {
                        return Mono.error(new IllegalStateException(
                                "Swap " + id + " is not PENDING/APPROVED, current: " + row.getStatus()));
                    }
                    String previous = row.getStatus();
                    row.setStatus("REJECTED");
                    row.setRejectionReason(reason);
                    row.setUpdatedAt(Instant.now());
                    return swapRepository.save(row)
                            .flatMap(saved -> publishAudit(saved, "UPDATE", actorId, actorEmail,
                                    Map.of("status", previous),
                                    Map.of("status", saved.getStatus(),
                                           "rejectionReason", reason == null ? "" : reason))
                                    .thenReturn(saved));
                });
    }

    /** Called by ScheduledStatusExecutor on the effective date. */
    @Transactional
    public Mono<MemberDependantSwap> apply(UUID id, String actorId, String actorEmail) {
        return swapRepository.findById(id)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Swap not found: " + id)))
                .flatMap(row -> {
                    if (!"APPROVED".equals(row.getStatus())) {
                        return Mono.error(new IllegalStateException(
                                "Swap " + id + " is not APPROVED, current: " + row.getStatus()));
                    }
                    return performSwap(row, actorId, actorEmail);
                });
    }

    // ── Private helpers ──────────────────────────────────────────────

    private Mono<Void> validate(UUID memberId, MemberSwapRequest req) {
        return memberRepository.findById(memberId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Member not found: " + memberId)))
                .flatMap(member -> dependantRepository.findById(req.dependantId())
                        .switchIfEmpty(Mono.error(new IllegalArgumentException(
                                "Dependant not found: " + req.dependantId())))
                        .flatMap(dep -> {
                            if (!memberId.equals(dep.getMemberId())) {
                                return Mono.error(new IllegalArgumentException(
                                        "Dependant does not belong to this member"));
                            }
                            String status = dep.getStatus();
                            if (!"active".equalsIgnoreCase(status)
                                    && !"suspended".equalsIgnoreCase(status)) {
                                return Mono.error(new IllegalArgumentException(
                                        "Dependant status must be active or suspended, was: " + status));
                            }
                            return Mono.empty();
                        }))
                .then();
    }

    private Mono<MemberDependantSwap> persistPending(MemberDependantSwap row,
                                                      String actorId, String actorEmail) {
        row.setStatus("PENDING");
        return swapRepository.save(row)
                .flatMap(saved -> publishAudit(saved, "CREATE", actorId, actorEmail, null,
                        Map.of("oldMemberId", saved.getOldMemberId().toString(),
                               "dependantId", saved.getDependantId().toString(),
                               "effectiveDate", saved.getEffectiveDate().toString(),
                               "status", saved.getStatus()))
                        .thenReturn(saved));
    }

    private Mono<MemberDependantSwap> applyImmediately(MemberDependantSwap row,
                                                        String actorId, String actorEmail) {
        row.setStatus("APPROVED");
        row.setApprovedBy(safeUuid(actorId));
        row.setApprovedAt(Instant.now());
        return swapRepository.save(row)
                .flatMap(saved -> performSwap(saved, actorId, actorEmail));
    }

    /**
     * Atomic role-swap. All eight steps run inside the enclosing
     * {@code @Transactional} boundary so any failure rolls the whole
     * lot back.
     */
    private Mono<MemberDependantSwap> performSwap(MemberDependantSwap row,
                                                    String actorId, String actorEmail) {
        return memberRepository.findById(row.getOldMemberId())
                .switchIfEmpty(Mono.error(new IllegalStateException(
                        "Old member vanished mid-swap: " + row.getOldMemberId())))
                .flatMap(oldMember -> dependantRepository.findById(row.getDependantId())
                        .switchIfEmpty(Mono.error(new IllegalStateException(
                                "Dependant vanished mid-swap: " + row.getDependantId())))
                        .flatMap(promotedDep -> promoteDependantToMember(oldMember, promotedDep)
                                .flatMap(newMember -> demoteOldMember(oldMember, newMember)
                                        .flatMap(newDep -> reparentSiblings(
                                                oldMember.getId(), promotedDep.getId(), newMember.getId())
                                                .then(markOldMemberSwapped(oldMember, newDep.getId()))
                                                .then(markOldDependantSwapped(promotedDep, newMember.getId()))
                                                .then(finalizeSwapRow(row, newMember.getId(), newDep.getId()))
                                                .flatMap(saved -> publishSwapAudit(saved, actorId, actorEmail)
                                                        .then(publishSwapEvent(saved, oldMember, newMember,
                                                                actorId, actorEmail))
                                                        .thenReturn(saved))))));
    }

    private Mono<Member> promoteDependantToMember(Member oldMember, Dependant dep) {
        return memberNumberService.nextMemberNumber().flatMap(memberNumber -> {
            Member m = new Member();
            m.setMemberNumber(memberNumber);
            m.setFirstName(dep.getFirstName());
            m.setLastName(dep.getLastName());
            m.setDateOfBirth(dep.getDateOfBirth());
            m.setGender(dep.getGender());
            m.setNationalId(dep.getNationalId());
            // Inherit policy context from old principal so billing routes
            // to the same group + scheme after the swap.
            m.setGroupId(oldMember.getGroupId());
            m.setSchemeId(oldMember.getSchemeId());
            m.setEnrollmentDate(oldMember.getEnrollmentDate());
            m.setStatus("active");
            m.setCreatedAt(Instant.now());
            m.setUpdatedAt(Instant.now());
            return memberRepository.save(m);
        });
    }

    private Mono<Dependant> demoteOldMember(Member oldMember, Member newMember) {
        return memberNumberService.nextDependantNumber(newMember).flatMap(memberNumber -> {
            Dependant d = new Dependant();
            d.setMemberId(newMember.getId());
            d.setMemberNumber(memberNumber);
            d.setFirstName(oldMember.getFirstName());
            d.setLastName(oldMember.getLastName());
            d.setDateOfBirth(oldMember.getDateOfBirth());
            d.setGender(oldMember.getGender());
            d.setNationalId(oldMember.getNationalId());
            d.setRelationship("SWAP_DEMOTED");
            d.setStatus("active");
            d.setEnrollmentDate(oldMember.getEnrollmentDate());
            d.setCreatedAt(Instant.now());
            d.setUpdatedAt(Instant.now());
            return dependantRepository.save(d);
        });
    }

    private Mono<Long> reparentSiblings(UUID oldMemberId, UUID promotedDependantId, UUID newMemberId) {
        return db.sql("""
                UPDATE dependants
                   SET member_id = :newMemberId, updated_at = NOW()
                 WHERE member_id = :oldMemberId AND id <> :promotedId
                """)
                .bind("newMemberId", newMemberId)
                .bind("oldMemberId", oldMemberId)
                .bind("promotedId", promotedDependantId)
                .fetch().rowsUpdated();
    }

    private Mono<Long> markOldMemberSwapped(Member oldMember, UUID newDependantId) {
        return db.sql("""
                UPDATE members
                   SET status = 'swapped', swapped_to_id = :swappedTo, updated_at = NOW()
                 WHERE id = :id
                """)
                .bind("swappedTo", newDependantId)
                .bind("id", oldMember.getId())
                .fetch().rowsUpdated();
    }

    private Mono<Long> markOldDependantSwapped(Dependant dep, UUID newMemberId) {
        return db.sql("""
                UPDATE dependants
                   SET status = 'swapped', swapped_to_id = :swappedTo, updated_at = NOW()
                 WHERE id = :id
                """)
                .bind("swappedTo", newMemberId)
                .bind("id", dep.getId())
                .fetch().rowsUpdated();
    }

    private Mono<MemberDependantSwap> finalizeSwapRow(MemberDependantSwap row,
                                                       UUID newMemberId, UUID newDependantId) {
        row.setNewMemberId(newMemberId);
        row.setOldDependantId(newDependantId);
        row.setStatus("APPLIED");
        row.setAppliedAt(Instant.now());
        row.setUpdatedAt(Instant.now());
        return swapRepository.save(row);
    }

    private Mono<Void> publishSwapAudit(MemberDependantSwap saved,
                                         String actorId, String actorEmail) {
        return publishAudit(saved, "UPDATE", actorId, actorEmail,
                Map.of("status", "APPROVED"),
                Map.of("status", saved.getStatus(),
                       "newMemberId", saved.getNewMemberId().toString(),
                       "oldDependantId", saved.getOldDependantId().toString()));
    }

    private Mono<Void> publishSwapEvent(MemberDependantSwap saved,
                                         Member oldMember, Member newMember,
                                         String actorId, String actorEmail) {
        boolean backdated = saved.getEffectiveDate() != null
                && saved.getEffectiveDate().isBefore(LocalDate.now().withDayOfMonth(1));
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            return eventPublisher.publishMemberChanged(
                    tenantId,
                    newMember.getId().toString(),
                    "SWAP_APPLIED",
                    oldMember.getId().toString(),
                    newMember.getId().toString(),
                    saved.getEffectiveDate() != null ? saved.getEffectiveDate().toString() : null,
                    backdated,
                    actorId, actorEmail);
        });
    }

    private Mono<Void> publishAudit(MemberDependantSwap saved, String action,
                                     String actorId, String actorEmail,
                                     Map<String, Object> oldValue, Map<String, Object> newValue) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            String name = "swap old-member " + saved.getOldMemberId()
                    + " <-> dependant " + saved.getDependantId();
            AuditEvent event = AuditEvent.create(
                    tenantId != null ? tenantId : "unknown",
                    "MemberSwap",
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

    private static UUID safeUuid(String s) {
        if (s == null) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }
}

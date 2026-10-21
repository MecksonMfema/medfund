package com.medfund.user.status;

import com.medfund.shared.lifecycle.StatusTransitionRecorder;
import com.medfund.user.entity.Member;
import com.medfund.user.exception.MemberNotFoundException;
import com.medfund.user.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Phase 13 §A per L3: the single write pathway for member status transitions.
 * Every flip — operator action, arrears consumer, scheduled roll, group
 * cascade, rules-engine auto-term — lands here so the entity update and its
 * {@code member_status_history} row commit atomically.
 *
 * <p>MemberService.transitionStatus retrofits onto {@link #applyTransition};
 * callers there keep their audit + MEMBER_STATUS_CHANGED publishes. New
 * callers (rules-engine auto-termination) use {@link #transition} directly.
 *
 * <p>Idempotent per plan invariant #13: a same-status transition is a no-op —
 * no history row, no save. History-row ordering: history row → entity save,
 * both inside one transaction.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberStatusTransitionService {

    private final MemberRepository memberRepository;
    private final StatusTransitionRecorder recorder;
    private final TransactionalOperator tx;

    /**
     * Load-by-id entry point for callers that only hold the member's UUID
     * (rules-engine auto-term). Rejects null/blank actorEmail up front per
     * feedback_audit_actor_email — every history row must say who did it.
     */
    public Mono<Member> transition(UUID memberId, String newStatus, String reasonCode,
                                   String reasonNote, UUID actorId, String actorEmail) {
        if (actorEmail == null || actorEmail.isBlank()) {
            return Mono.error(new IllegalArgumentException(
                    "actorEmail is required for a member status transition (member " + memberId + ")"));
        }
        return memberRepository.findById(memberId)
                .switchIfEmpty(Mono.error(new MemberNotFoundException(memberId)))
                .flatMap(existing -> applyTransition(existing, newStatus, reasonCode, reasonNote,
                        actorId, actorEmail));
    }

    /**
     * Core transition over an already-loaded member — used by
     * MemberService.transitionStatus (which needs the pre-state for its audit
     * diff) and by {@link #transition}. Mutates the instance in place:
     * status set, scheduled trio consumed, V043 suspend_reason maintained.
     */
    public Mono<Member> applyTransition(Member existing, String newStatus, String reasonCode,
                                        String reasonNote, UUID actorId, String actorEmail) {
        if (actorEmail == null || actorEmail.isBlank()) {
            return Mono.error(new IllegalArgumentException(
                    "actorEmail is required for a member status transition (member " + existing.getId() + ")"));
        }
        String oldStatus = existing.getStatus();
        if (oldStatus != null && oldStatus.equalsIgnoreCase(newStatus)) {
            log.debug("Skipping same-status member transition {} ({} → {})",
                    existing.getId(), oldStatus, newStatus);
            return Mono.just(existing);
        }

        existing.setStatus(newStatus);
        // Clear the scheduled trio — the roll job (or an apply-now) has
        // consumed the schedule.
        existing.setScheduledStatus(null);
        existing.setScheduledStatusEffectiveFrom(null);
        existing.setScheduledStatusReason(null);
        // V043 — persist the reason for non-active transitions so the
        // arrears executor can find arrears-suspended rows without walking
        // the audit trail. Clear on return to active.
        if ("active".equals(newStatus)) {
            existing.setSuspendReason(null);
        } else if (reasonNote != null && !reasonNote.isBlank()) {
            existing.setSuspendReason(reasonNote);
        }
        existing.setUpdatedAt(Instant.now());
        existing.setUpdatedBy(actorId);

        OffsetDateTime effectiveAt = OffsetDateTime.now();
        String code = reasonCode != null && !reasonCode.isBlank() ? reasonCode : defaultReasonCodeFor(newStatus);
        return recorder.recordMember(existing.getId(), oldStatus, newStatus, effectiveAt,
                        actorId, actorEmail, code,
                        reasonNote != null && !reasonNote.isBlank() ? reasonNote : null)
                // Deferred so the entity save is only assembled once the
                // history row is durably recorded — a failed record must not
                // even build the update, let alone commit it.
                .then(Mono.defer(() -> memberRepository.save(existing)))
                .as(tx::transactional)
                .doOnSuccess(saved -> log.info("Member {} transitioned {} → {} by {} ({})",
                        saved.getId(), oldStatus, newStatus, actorEmail, code));
    }

    /**
     * Fallback reason-code attribution when a caller doesn't supply one.
     * Keeps every auto-derived row inside the V112 CHECK vocabulary; flows
     * that know better (arrears breach → 'arrears_lapse') pass their own code.
     */
    public static String defaultReasonCodeFor(String targetStatus) {
        return switch (targetStatus == null ? "" : targetStatus) {
            case "active" -> "admin_activate";
            case "suspended" -> "admin_suspend";
            case "terminated" -> "admin_terminate";
            case "deactivated" -> "admin_deactivate";
            case "lapsed" -> "admin_lapse";
            default -> "other";
        };
    }
}

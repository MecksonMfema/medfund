package com.medfund.user.job;

import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.scheduler.JobExecutor;
import com.medfund.shared.scheduler.JobType;
import com.medfund.user.entity.Group;
import com.medfund.user.entity.Member;
import com.medfund.user.repository.DependantRepository;
import com.medfund.user.repository.GroupRepository;
import com.medfund.user.repository.MemberDependantSwapRepository;
import com.medfund.user.repository.MemberRepository;
import com.medfund.user.repository.PendingGroupChangeRepository;
import com.medfund.user.service.DependantService;
import com.medfund.user.service.GroupChangeService;
import com.medfund.user.service.GroupService;
import com.medfund.user.service.MemberService;
import com.medfund.user.service.MemberSwapService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Daily executor that rolls two classes of change forward:
 *
 * <ol>
 *   <li><b>Enrolments</b> — any member with {@code status = 'enrolled'} and
 *   an {@code enrollment_date <= today} is flipped to {@code active}. That
 *   supports the "enrol today, effective from next month" flow: the row
 *   stays {@code enrolled} until the day arrives.</li>
 *
 *   <li><b>Scheduled status changes</b> — any member or group whose
 *   {@code scheduled_status_effective_from <= today} is flipped to its
 *   {@code scheduled_status}. Reason is passed through so downstream
 *   consumers can distinguish OPERATOR from ARREARS_ESCALATION.</li>
 * </ol>
 *
 * <p>Each match calls back through {@link MemberService}/
 * {@link GroupService} using the standard "apply now" branch so the
 * MEMBER_STATUS_CHANGED / group audit events fire naturally and the
 * scheduled trio is cleared as part of the transaction.
 *
 * <p>Errors per row are logged and swallowed — a single bad row must
 * not block the rest of the sweep.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduledStatusExecutor implements JobExecutor {

    private final MemberService memberService;
    private final GroupService groupService;
    private final DependantService dependantService;
    private final GroupChangeService groupChangeService;
    private final MemberSwapService memberSwapService;
    private final MemberRepository memberRepository;
    private final GroupRepository groupRepository;
    private final DependantRepository dependantRepository;
    private final PendingGroupChangeRepository pendingGroupChangeRepository;
    private final MemberDependantSwapRepository memberDependantSwapRepository;
    // Reserved for future direct SQL touch-ups (bulk clear scheduled
    // trio on already-transitioned rows, health-check reports, …).
    private final DatabaseClient db;

    @Override
    public JobType getJobType() {
        return JobType.SCHEDULED_STATUS_ROLL;
    }

    @Override
    public Mono<Void> execute(String tenantId, String settings) {
        log.info("Rolling scheduled status changes for tenant: {}", tenantId);
        LocalDate today = LocalDate.now();
        // System actor — same convention BILLING_CYCLE uses for
        // scheduler-owned lifecycle transitions.
        String actorId = AuditActor.SYSTEM_ID;
        String actorEmail = AuditActor.SYSTEM_EMAIL;

        Flux<Void> enrolments = memberRepository.findAll()
                .filter(m -> "enrolled".equals(m.getStatus()))
                .filter(m -> m.getEnrollmentDate() != null && !m.getEnrollmentDate().isAfter(today))
                .flatMap(m -> memberService.activate(m.getId(), null, "SCHEDULED_ENROLMENT",
                                actorId, actorEmail)
                        .doOnNext(saved -> log.info("Rolled enrolled → active for member {}", saved.getId()))
                        .then(Mono.<Void>empty())
                        .onErrorResume(err -> {
                            log.warn("Enrolment roll failed for member {}: {}", m.getId(), err.getMessage());
                            return Mono.empty();
                        }));

        // V048: same shape for dependants — a future-dated dependant
        // sits at 'enrolled' until the day arrives, then flips to
        // 'active' here so the resolver includes them from that cycle.
        Flux<Void> dependantEnrolments = dependantRepository.findAll()
                .filter(d -> "enrolled".equals(d.getStatus()))
                .filter(d -> d.getEnrollmentDate() != null && !d.getEnrollmentDate().isAfter(today))
                .flatMap(d -> dependantService.activate(d.getId(), actorId, actorEmail)
                        .doOnNext(saved -> log.info("Rolled enrolled → active for dependant {}", saved.getId()))
                        .then(Mono.<Void>empty())
                        .onErrorResume(err -> {
                            log.warn("Enrolment roll failed for dependant {}: {}", d.getId(), err.getMessage());
                            return Mono.empty();
                        }));

        Flux<Void> memberSchedules = memberRepository.findAll()
                .filter(m -> m.getScheduledStatus() != null
                        && m.getScheduledStatusEffectiveFrom() != null
                        && !m.getScheduledStatusEffectiveFrom().isAfter(today))
                .flatMap(m -> applyMemberSchedule(m, actorId, actorEmail)
                        .onErrorResume(err -> {
                            log.warn("Scheduled member roll failed for {}: {}", m.getId(), err.getMessage());
                            return Mono.empty();
                        }));

        Flux<Void> groupSchedules = groupRepository.findAll()
                .filter(g -> g.getScheduledStatus() != null
                        && g.getScheduledStatusEffectiveFrom() != null
                        && !g.getScheduledStatusEffectiveFrom().isAfter(today))
                .flatMap(g -> applyGroupSchedule(g, actorId, actorEmail)
                        .onErrorResume(err -> {
                            log.warn("Scheduled group roll failed for {}: {}", g.getId(), err.getMessage());
                            return Mono.empty();
                        }));

        // V048: pending group-change requests whose effective_date has
        // arrived. Sweeps every APPROVED row; skipped rows (PENDING /
        // REJECTED / APPLIED) stay behind for the next tick or archival.
        Flux<Void> groupChanges = pendingGroupChangeRepository.findReadyToApply(today)
                .flatMap(pc -> groupChangeService.apply(pc.getId(), actorId, actorEmail)
                        .doOnNext(saved -> log.info(
                                "Applied group change {} for member {} → group {}",
                                saved.getId(), saved.getMemberId(), saved.getToGroupId()))
                        .then(Mono.<Void>empty())
                        .onErrorResume(err -> {
                            log.warn("Group change apply failed for {}: {}", pc.getId(), err.getMessage());
                            return Mono.empty();
                        }));

        // V048: pending member↔dependant swaps whose effective_date has
        // arrived. Same swallow-and-continue idiom — a broken swap row
        // must not block the rest of the sweep.
        Flux<Void> swaps = memberDependantSwapRepository.findReadyToApply(today)
                .flatMap(s -> memberSwapService.apply(s.getId(), actorId, actorEmail)
                        .doOnNext(saved -> log.info(
                                "Applied swap {} — dependant {} promoted to member {}",
                                saved.getId(), saved.getDependantId(), saved.getNewMemberId()))
                        .then(Mono.<Void>empty())
                        .onErrorResume(err -> {
                            log.warn("Swap apply failed for {}: {}", s.getId(), err.getMessage());
                            return Mono.empty();
                        }));

        return Flux.concat(enrolments, dependantEnrolments, memberSchedules, groupSchedules,
                groupChanges, swaps).then();
    }

    /**
     * Dispatch to the right MemberService transition based on the row's
     * scheduled_status target. Reason threads through so the resulting
     * lifecycle event carries the operator's original justification.
     */
    private Mono<Void> applyMemberSchedule(Member m, String actorId, String actorEmail) {
        UUID id = m.getId();
        String target = m.getScheduledStatus();
        String reason = m.getScheduledStatusReason();
        Mono<Member> op = switch (target) {
            case "active"       -> memberService.activate(id, null, reason, actorId, actorEmail);
            case "suspended"    -> memberService.suspend(id, null, reason, actorId, actorEmail);
            case "terminated"   -> memberService.terminate(id, null, reason, actorId, actorEmail);
            case "deactivated"  -> memberService.deactivate(id, null, reason, actorId, actorEmail);
            default -> Mono.error(new IllegalStateException(
                    "Unknown scheduled_status '" + target + "' on member " + id));
        };
        return op.doOnNext(saved -> log.info("Rolled member {} to {}", id, saved.getStatus()))
                .then();
    }

    private Mono<Void> applyGroupSchedule(Group g, String actorId, String actorEmail) {
        UUID id = g.getId();
        String target = g.getScheduledStatus();
        String reason = g.getScheduledStatusReason();
        Mono<Group> op = switch (target) {
            case "active"       -> groupService.activate(id, null, reason, actorId, actorEmail);
            case "suspended"    -> groupService.suspend(id, null, reason, actorId, actorEmail);
            case "terminated"   -> groupService.terminate(id, null, reason, actorId, actorEmail);
            case "deactivated"  -> groupService.deactivate(id, null, reason, actorId, actorEmail);
            default -> Mono.error(new IllegalStateException(
                    "Unknown scheduled_status '" + target + "' on group " + id));
        };
        return op.doOnNext(saved -> log.info("Rolled group {} to {}", id, saved.getStatus()))
                .then();
    }
}

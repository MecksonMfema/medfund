package com.medfund.user.scheduler;

import com.medfund.shared.scheduler.JobType;
import com.medfund.user.entity.Group;
import com.medfund.user.entity.Member;
import com.medfund.user.job.ScheduledStatusExecutor;
import com.medfund.user.repository.DependantRepository;
import com.medfund.user.repository.GroupRepository;
import com.medfund.user.repository.MemberRepository;
import com.medfund.user.repository.PendingGroupChangeRepository;
import com.medfund.user.service.DependantService;
import com.medfund.user.service.GroupChangeService;
import com.medfund.user.service.GroupService;
import com.medfund.user.service.MemberService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScheduledStatusExecutorTest {

    @Mock MemberService memberService;
    @Mock GroupService groupService;
    @Mock DependantService dependantService;
    @Mock GroupChangeService groupChangeService;
    @Mock MemberRepository memberRepository;
    @Mock GroupRepository groupRepository;
    @Mock DependantRepository dependantRepository;
    @Mock PendingGroupChangeRepository pendingGroupChangeRepository;
    @Mock DatabaseClient databaseClient;

    private ScheduledStatusExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new ScheduledStatusExecutor(memberService, groupService, dependantService,
                groupChangeService, memberRepository, groupRepository, dependantRepository,
                pendingGroupChangeRepository, databaseClient);
        // Default: no dependants in the sweep unless the test says otherwise.
        org.mockito.Mockito.lenient().when(dependantRepository.findAll())
                .thenReturn(Flux.empty());
        // Default: no ready group changes in the sweep. Individual tests
        // override for the V048 group-change apply case.
        org.mockito.Mockito.lenient().when(pendingGroupChangeRepository.findReadyToApply(any()))
                .thenReturn(Flux.empty());
    }

    @Test
    void jobType_matchesEnum() {
        assertThat(executor.getJobType()).isEqualTo(JobType.SCHEDULED_STATUS_ROLL);
    }

    @Test
    void enrolled_dueToday_flippedToActive() {
        Member due = member(UUID.randomUUID(), "enrolled", LocalDate.now().minusDays(1), null, null, null);
        when(memberRepository.findAll()).thenReturn(Flux.just(due));
        when(groupRepository.findAll()).thenReturn(Flux.empty());
        when(memberService.activate(eq(due.getId()), isNull(), eq("SCHEDULED_ENROLMENT"),
                any(), any())).thenReturn(Mono.just(due));

        StepVerifier.create(executor.execute("tnt", "{}")).verifyComplete();

        verify(memberService).activate(eq(due.getId()), isNull(), eq("SCHEDULED_ENROLMENT"),
                any(), any());
    }

    @Test
    void enrolled_futureDate_notFlipped() {
        Member future = member(UUID.randomUUID(), "enrolled", LocalDate.now().plusDays(3), null, null, null);
        when(memberRepository.findAll()).thenReturn(Flux.just(future));
        when(groupRepository.findAll()).thenReturn(Flux.empty());

        StepVerifier.create(executor.execute("tnt", "{}")).verifyComplete();

        verify(memberService, never()).activate(any(), any(), any(), any(), any());
    }

    @Test
    void scheduledMember_dueToday_routesToTargetStatus() {
        UUID id = UUID.randomUUID();
        Member suspend = member(id, "active", LocalDate.now().minusDays(30),
                "suspended", LocalDate.now(), "OPERATOR");
        when(memberRepository.findAll()).thenReturn(Flux.just(suspend));
        when(groupRepository.findAll()).thenReturn(Flux.empty());
        when(memberService.suspend(eq(id), isNull(), eq("OPERATOR"), any(), any()))
                .thenReturn(Mono.just(suspend));

        StepVerifier.create(executor.execute("tnt", "{}")).verifyComplete();

        verify(memberService).suspend(eq(id), isNull(), eq("OPERATOR"), any(), any());
        verify(memberService, never()).terminate(any(), any(), any(), any(), any());
        verify(memberService, never()).deactivate(any(), any(), any(), any(), any());
    }

    @Test
    void scheduledMember_futureDate_notApplied() {
        UUID id = UUID.randomUUID();
        Member future = member(id, "active", LocalDate.now().minusDays(30),
                "deactivated", LocalDate.now().plusDays(2), "OPERATOR");
        when(memberRepository.findAll()).thenReturn(Flux.just(future));
        when(groupRepository.findAll()).thenReturn(Flux.empty());

        StepVerifier.create(executor.execute("tnt", "{}")).verifyComplete();

        verify(memberService, never()).deactivate(any(), any(), any(), any(), any());
    }

    @Test
    void scheduledGroup_dueToday_routesDeactivate() {
        UUID id = UUID.randomUUID();
        Group group = group(id, "active", "deactivated", LocalDate.now(), "OPERATOR");
        when(memberRepository.findAll()).thenReturn(Flux.empty());
        when(groupRepository.findAll()).thenReturn(Flux.just(group));
        when(groupService.deactivate(eq(id), isNull(), eq("OPERATOR"), any(), any()))
                .thenReturn(Mono.just(group));

        StepVerifier.create(executor.execute("tnt", "{}")).verifyComplete();

        verify(groupService).deactivate(eq(id), isNull(), eq("OPERATOR"), any(), any());
    }

    @Test
    void perRowErrors_areSwallowed_soSweepContinues() {
        Member bad = member(UUID.randomUUID(), "active", LocalDate.now().minusDays(30),
                "suspended", LocalDate.now(), "REASON");
        Member good = member(UUID.randomUUID(), "active", LocalDate.now().minusDays(30),
                "terminated", LocalDate.now(), "REASON");
        when(memberRepository.findAll()).thenReturn(Flux.just(bad, good));
        when(groupRepository.findAll()).thenReturn(Flux.empty());
        when(memberService.suspend(eq(bad.getId()), isNull(), any(), any(), any()))
                .thenReturn(Mono.error(new RuntimeException("boom")));
        when(memberService.terminate(eq(good.getId()), isNull(), any(), any(), any()))
                .thenReturn(Mono.just(good));

        StepVerifier.create(executor.execute("tnt", "{}")).verifyComplete();

        verify(memberService).suspend(eq(bad.getId()), isNull(), any(), any(), any());
        verify(memberService).terminate(eq(good.getId()), isNull(), any(), any(), any());
    }

    @Test
    void pendingGroupChange_dueToday_appliedThroughService() {
        // V048 — ScheduledChangesExecutor sweep: an APPROVED
        // pending_group_change row whose effective_date has arrived
        // must call GroupChangeService.apply so the member's group
        // flips + a MEMBER_CHANGED event fires.
        when(memberRepository.findAll()).thenReturn(Flux.empty());
        when(groupRepository.findAll()).thenReturn(Flux.empty());
        com.medfund.user.entity.PendingGroupChange pc = new com.medfund.user.entity.PendingGroupChange();
        pc.setId(UUID.randomUUID());
        pc.setMemberId(UUID.randomUUID());
        pc.setToGroupId(UUID.randomUUID());
        pc.setStatus("APPROVED");
        pc.setEffectiveDate(LocalDate.now().withDayOfMonth(1));
        when(pendingGroupChangeRepository.findReadyToApply(any()))
                .thenReturn(Flux.just(pc));
        when(groupChangeService.apply(eq(pc.getId()), any(), any()))
                .thenReturn(Mono.just(pc));

        StepVerifier.create(executor.execute("tnt", "{}")).verifyComplete();

        verify(groupChangeService).apply(eq(pc.getId()), any(), any());
    }

    @Test
    void pendingGroupChange_applyErrors_areSwallowed_soOtherSweepsFinish() {
        // A broken group-change row must NOT block downstream sweeps
        // or subsequent group-change rows. Same swallow-and-continue
        // idiom as the member-status sweep.
        when(memberRepository.findAll()).thenReturn(Flux.empty());
        when(groupRepository.findAll()).thenReturn(Flux.empty());
        com.medfund.user.entity.PendingGroupChange bad = new com.medfund.user.entity.PendingGroupChange();
        bad.setId(UUID.randomUUID());
        bad.setMemberId(UUID.randomUUID());
        bad.setToGroupId(UUID.randomUUID());
        bad.setStatus("APPROVED");
        bad.setEffectiveDate(LocalDate.now().withDayOfMonth(1));
        when(pendingGroupChangeRepository.findReadyToApply(any()))
                .thenReturn(Flux.just(bad));
        when(groupChangeService.apply(eq(bad.getId()), any(), any()))
                .thenReturn(Mono.error(new RuntimeException("boom")));

        StepVerifier.create(executor.execute("tnt", "{}")).verifyComplete();

        verify(groupChangeService).apply(eq(bad.getId()), any(), any());
    }

    private static Member member(UUID id, String status, LocalDate enrollmentDate,
                                  String scheduledStatus, LocalDate scheduledFrom, String reason) {
        Member m = new Member();
        m.setId(id);
        m.setStatus(status);
        m.setEnrollmentDate(enrollmentDate);
        m.setScheduledStatus(scheduledStatus);
        m.setScheduledStatusEffectiveFrom(scheduledFrom);
        m.setScheduledStatusReason(reason);
        return m;
    }

    private static Group group(UUID id, String status,
                                String scheduledStatus, LocalDate scheduledFrom, String reason) {
        Group g = new Group();
        g.setId(id);
        g.setStatus(status);
        g.setScheduledStatus(scheduledStatus);
        g.setScheduledStatusEffectiveFrom(scheduledFrom);
        g.setScheduledStatusReason(reason);
        return g;
    }
}

package com.medfund.user.service;

import com.medfund.shared.audit.AuditPublisher;
import com.medfund.user.dto.GroupChangeRequest;
import com.medfund.user.entity.Group;
import com.medfund.user.entity.Member;
import com.medfund.user.entity.PendingGroupChange;
import com.medfund.user.repository.GroupRepository;
import com.medfund.user.repository.MemberRepository;
import com.medfund.user.repository.PendingGroupChangeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the request/approve/reject/apply state machine and the
 * back-dating fast-path. The critical invariant is that a back-dated
 * request skips PENDING entirely and publishes a MEMBER_CHANGED event
 * with {@code backdated=true} so contributions-service posts the
 * arrears/rebate. A forward-dated request stays PENDING until an
 * operator approves it; the executor calls {@link
 * GroupChangeService#apply} on the effective date.
 */
@ExtendWith(MockitoExtension.class)
class GroupChangeServiceTest {

    @Mock private PendingGroupChangeRepository repository;
    @Mock private MemberRepository memberRepository;
    @Mock private GroupRepository groupRepository;
    @Mock private AuditPublisher auditPublisher;
    @Mock private UserEventPublisher eventPublisher;

    private GroupChangeService service;

    @BeforeEach
    void setUp() {
        service = new GroupChangeService(repository, memberRepository, groupRepository,
                auditPublisher, eventPublisher);
        // Every path publishes an audit event and, on apply/backdated,
        // an event. Stub lenient so tests can focus on the interesting
        // assertions.
        lenient().when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        lenient().when(eventPublisher.publishMemberChanged(
                any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any()))
                .thenReturn(Mono.empty());
    }

    // ------------------------------------------------------------------
    // request — forward-dated → PENDING
    // ------------------------------------------------------------------

    @Test
    void request_forwardDated_stashesPending_doesNotFlipGroup_doesNotPublishEvent() {
        UUID memberId = UUID.randomUUID();
        UUID oldGroup = UUID.randomUUID();
        UUID newGroup = UUID.randomUUID();
        LocalDate effective = LocalDate.now().withDayOfMonth(1).plusMonths(1);

        Member member = new Member();
        member.setId(memberId);
        member.setGroupId(oldGroup);
        Group target = new Group();
        target.setId(newGroup);
        target.setStatus("active");

        when(memberRepository.findById(memberId)).thenReturn(Mono.just(member));
        when(groupRepository.findById(newGroup)).thenReturn(Mono.just(target));
        when(repository.countLiveByMemberId(memberId)).thenReturn(Mono.just(0L));
        when(repository.save(any(PendingGroupChange.class)))
                .thenAnswer(inv -> {
                    PendingGroupChange row = inv.getArgument(0);
                    row.setId(UUID.randomUUID());
                    return Mono.just(row);
                });

        var req = new GroupChangeRequest(newGroup, effective, "moving offices");

        StepVerifier.create(service.request(memberId, req, UUID.randomUUID().toString(), "op@medfund.com"))
                .assertNext(saved -> {
                    assertThat(saved.getStatus()).isEqualTo("PENDING");
                    assertThat(saved.getFromGroupId()).isEqualTo(oldGroup);
                    assertThat(saved.getToGroupId()).isEqualTo(newGroup);
                    assertThat(saved.getEffectiveDate()).isEqualTo(effective);
                })
                .verifyComplete();

        // Group is NOT flipped on the member row yet — the executor
        // will do that on the effective date after approval.
        verify(memberRepository, never()).save(any(Member.class));
        // Forward-dated → no MEMBER_CHANGED event fires now.
        verify(eventPublisher, never()).publishMemberChanged(
                any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any());
    }

    // ------------------------------------------------------------------
    // request — back-dated → APPLIED immediately + MEMBER_CHANGED with backdated=true
    // ------------------------------------------------------------------

    @Test
    void request_backDated_appliesImmediately_flipsGroup_publishesBackdatedEvent() {
        UUID memberId = UUID.randomUUID();
        UUID oldGroup = UUID.randomUUID();
        UUID newGroup = UUID.randomUUID();
        // Prior month → back-dated for sure.
        LocalDate effective = LocalDate.now().withDayOfMonth(1).minusMonths(2);

        Member member = new Member();
        member.setId(memberId);
        member.setGroupId(oldGroup);
        Group target = new Group();
        target.setId(newGroup);
        target.setStatus("active");

        when(memberRepository.findById(memberId)).thenReturn(Mono.just(member));
        when(groupRepository.findById(newGroup)).thenReturn(Mono.just(target));
        when(repository.countLiveByMemberId(memberId)).thenReturn(Mono.just(0L));
        when(memberRepository.save(any(Member.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(repository.save(any(PendingGroupChange.class)))
                .thenAnswer(inv -> {
                    PendingGroupChange row = inv.getArgument(0);
                    row.setId(UUID.randomUUID());
                    return Mono.just(row);
                });

        var req = new GroupChangeRequest(newGroup, effective, "correction");

        StepVerifier.create(service.request(memberId, req, UUID.randomUUID().toString(), "op@medfund.com"))
                .assertNext(saved -> {
                    assertThat(saved.getStatus()).isEqualTo("APPLIED");
                    assertThat(saved.getAppliedAt()).isNotNull();
                })
                .verifyComplete();

        // Members row's group_id is flipped.
        ArgumentCaptor<Member> memberCaptor = ArgumentCaptor.forClass(Member.class);
        verify(memberRepository).save(memberCaptor.capture());
        assertThat(memberCaptor.getValue().getGroupId()).isEqualTo(newGroup);

        // MEMBER_CHANGED event fires with backdated=true.
        verify(eventPublisher).publishMemberChanged(
                any(), eq(memberId.toString()), eq("GROUP_CHANGE"),
                eq(oldGroup.toString()), eq(newGroup.toString()),
                eq(effective.toString()), eq(true), any(), any());
    }

    // ------------------------------------------------------------------
    // request — validation errors
    // ------------------------------------------------------------------

    @Test
    void request_targetSameAsCurrent_errors() {
        UUID memberId = UUID.randomUUID();
        UUID sameGroup = UUID.randomUUID();
        Member member = new Member();
        member.setId(memberId);
        member.setGroupId(sameGroup);
        when(memberRepository.findById(memberId)).thenReturn(Mono.just(member));

        var req = new GroupChangeRequest(sameGroup, LocalDate.now().withDayOfMonth(1).plusMonths(1), null);

        StepVerifier.create(service.request(memberId, req, UUID.randomUUID().toString(), "op@medfund.com"))
                .expectErrorSatisfies(t -> assertThat(t)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("same as current"))
                .verify();
    }

    @Test
    void request_liveRequestExists_errors() {
        UUID memberId = UUID.randomUUID();
        UUID oldGroup = UUID.randomUUID();
        UUID newGroup = UUID.randomUUID();
        Member member = new Member();
        member.setId(memberId);
        member.setGroupId(oldGroup);
        Group target = new Group();
        target.setId(newGroup);
        target.setStatus("active");

        when(memberRepository.findById(memberId)).thenReturn(Mono.just(member));
        when(groupRepository.findById(newGroup)).thenReturn(Mono.just(target));
        when(repository.countLiveByMemberId(memberId)).thenReturn(Mono.just(1L));

        var req = new GroupChangeRequest(newGroup, LocalDate.now().withDayOfMonth(1).plusMonths(1), null);

        StepVerifier.create(service.request(memberId, req, UUID.randomUUID().toString(), "op@medfund.com"))
                .expectErrorSatisfies(t -> assertThat(t)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("live group change"))
                .verify();
    }

    @Test
    void request_inactiveTargetGroup_errors() {
        UUID memberId = UUID.randomUUID();
        UUID oldGroup = UUID.randomUUID();
        UUID newGroup = UUID.randomUUID();
        Member member = new Member();
        member.setId(memberId);
        member.setGroupId(oldGroup);
        Group target = new Group();
        target.setId(newGroup);
        target.setStatus("suspended");

        when(memberRepository.findById(memberId)).thenReturn(Mono.just(member));
        when(groupRepository.findById(newGroup)).thenReturn(Mono.just(target));

        var req = new GroupChangeRequest(newGroup, LocalDate.now().withDayOfMonth(1).plusMonths(1), null);

        StepVerifier.create(service.request(memberId, req, UUID.randomUUID().toString(), "op@medfund.com"))
                .expectErrorSatisfies(t -> assertThat(t)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("not active"))
                .verify();
    }

    @Test
    void request_unknownMember_errors() {
        UUID memberId = UUID.randomUUID();
        UUID newGroup = UUID.randomUUID();
        when(memberRepository.findById(memberId)).thenReturn(Mono.empty());

        var req = new GroupChangeRequest(newGroup, LocalDate.now().withDayOfMonth(1).plusMonths(1), null);

        StepVerifier.create(service.request(memberId, req, UUID.randomUUID().toString(), "op@medfund.com"))
                .expectErrorSatisfies(t -> assertThat(t)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("Member not found"))
                .verify();
    }

    @Test
    void request_unknownTargetGroup_errors() {
        UUID memberId = UUID.randomUUID();
        UUID oldGroup = UUID.randomUUID();
        UUID newGroup = UUID.randomUUID();
        Member member = new Member();
        member.setId(memberId);
        member.setGroupId(oldGroup);
        when(memberRepository.findById(memberId)).thenReturn(Mono.just(member));
        when(groupRepository.findById(newGroup)).thenReturn(Mono.empty());

        var req = new GroupChangeRequest(newGroup, LocalDate.now().withDayOfMonth(1).plusMonths(1), null);

        StepVerifier.create(service.request(memberId, req, UUID.randomUUID().toString(), "op@medfund.com"))
                .expectErrorSatisfies(t -> assertThat(t)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("Target group not found"))
                .verify();
    }

    // ------------------------------------------------------------------
    // approve
    // ------------------------------------------------------------------

    @Test
    void approve_flipsToApproved_capturesActor() {
        UUID id = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        PendingGroupChange row = new PendingGroupChange();
        row.setId(id);
        row.setMemberId(UUID.randomUUID());
        row.setToGroupId(UUID.randomUUID());
        row.setStatus("PENDING");

        when(repository.findById(id)).thenReturn(Mono.just(row));
        when(repository.save(any(PendingGroupChange.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service.approve(id, actorId.toString(), "boss@medfund.com"))
                .assertNext(saved -> {
                    assertThat(saved.getStatus()).isEqualTo("APPROVED");
                    assertThat(saved.getApprovedBy()).isEqualTo(actorId);
                    assertThat(saved.getApprovedAt()).isNotNull();
                })
                .verifyComplete();
    }

    @Test
    void approve_nonPending_errors() {
        UUID id = UUID.randomUUID();
        PendingGroupChange row = new PendingGroupChange();
        row.setId(id);
        row.setMemberId(UUID.randomUUID());
        row.setToGroupId(UUID.randomUUID());
        row.setStatus("APPROVED");
        when(repository.findById(id)).thenReturn(Mono.just(row));

        StepVerifier.create(service.approve(id, UUID.randomUUID().toString(), "boss@medfund.com"))
                .expectErrorSatisfies(t -> assertThat(t)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("not PENDING"))
                .verify();
    }

    @Test
    void approve_notFound_errors() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Mono.empty());

        StepVerifier.create(service.approve(id, UUID.randomUUID().toString(), "boss@medfund.com"))
                .expectErrorSatisfies(t -> assertThat(t)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("not found"))
                .verify();
    }

    // ------------------------------------------------------------------
    // reject
    // ------------------------------------------------------------------

    @Test
    void reject_capturesReason() {
        UUID id = UUID.randomUUID();
        PendingGroupChange row = new PendingGroupChange();
        row.setId(id);
        row.setMemberId(UUID.randomUUID());
        row.setToGroupId(UUID.randomUUID());
        row.setStatus("PENDING");

        when(repository.findById(id)).thenReturn(Mono.just(row));
        when(repository.save(any(PendingGroupChange.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service.reject(id, "duplicate request", UUID.randomUUID().toString(), "boss@medfund.com"))
                .assertNext(saved -> {
                    assertThat(saved.getStatus()).isEqualTo("REJECTED");
                    assertThat(saved.getRejectionReason()).isEqualTo("duplicate request");
                })
                .verifyComplete();
    }

    // ------------------------------------------------------------------
    // apply — normal executor path
    // ------------------------------------------------------------------

    @Test
    void apply_flipsMemberGroup_publishesForwardDatedEvent() {
        UUID id = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        UUID oldGroup = UUID.randomUUID();
        UUID newGroup = UUID.randomUUID();
        LocalDate effective = LocalDate.now().withDayOfMonth(1); // current month

        PendingGroupChange row = new PendingGroupChange();
        row.setId(id);
        row.setMemberId(memberId);
        row.setFromGroupId(oldGroup);
        row.setToGroupId(newGroup);
        row.setEffectiveDate(effective);
        row.setStatus("APPROVED");

        Member member = new Member();
        member.setId(memberId);
        member.setGroupId(oldGroup);

        when(repository.findById(id)).thenReturn(Mono.just(row));
        when(memberRepository.findById(memberId)).thenReturn(Mono.just(member));
        when(memberRepository.save(any(Member.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(repository.save(any(PendingGroupChange.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service.apply(id, UUID.randomUUID().toString(), "op@medfund.com"))
                .assertNext(saved -> {
                    assertThat(saved.getStatus()).isEqualTo("APPLIED");
                    assertThat(saved.getAppliedAt()).isNotNull();
                })
                .verifyComplete();

        // Current-month effective date → backdated=false on the event.
        verify(eventPublisher).publishMemberChanged(
                any(), eq(memberId.toString()), eq("GROUP_CHANGE"),
                eq(oldGroup.toString()), eq(newGroup.toString()),
                eq(effective.toString()), eq(false), any(), any());
    }

    @Test
    void apply_nonApproved_errors() {
        UUID id = UUID.randomUUID();
        PendingGroupChange row = new PendingGroupChange();
        row.setId(id);
        row.setMemberId(UUID.randomUUID());
        row.setToGroupId(UUID.randomUUID());
        row.setStatus("PENDING");
        when(repository.findById(id)).thenReturn(Mono.just(row));

        StepVerifier.create(service.apply(id, UUID.randomUUID().toString(), "op@medfund.com"))
                .expectErrorSatisfies(t -> assertThat(t)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("not APPROVED"))
                .verify();
    }
}

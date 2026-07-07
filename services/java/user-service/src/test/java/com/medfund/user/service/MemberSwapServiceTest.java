package com.medfund.user.service;

import com.medfund.shared.audit.AuditPublisher;
import com.medfund.user.dto.MemberSwapRequest;
import com.medfund.user.entity.Dependant;
import com.medfund.user.entity.Member;
import com.medfund.user.entity.MemberDependantSwap;
import com.medfund.user.repository.DependantRepository;
import com.medfund.user.repository.MemberDependantSwapRepository;
import com.medfund.user.repository.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.r2dbc.core.DatabaseClient;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Guards the request/approve/apply state machine for
 * MemberSwapService and the immediate-apply fast-path on back-dating.
 * The full atomic swap (promote/demote/re-parent/mark-swapped) is
 * covered by a follow-up IT — this unit test focuses on state
 * transitions + validation.
 */
@ExtendWith(MockitoExtension.class)
class MemberSwapServiceTest {

    @Mock MemberDependantSwapRepository swapRepository;
    @Mock MemberRepository memberRepository;
    @Mock DependantRepository dependantRepository;
    @Mock MemberNumberService memberNumberService;
    @Mock AuditPublisher auditPublisher;
    @Mock UserEventPublisher eventPublisher;
    @Mock DatabaseClient db;

    private MemberSwapService service;

    @BeforeEach
    void setUp() {
        service = new MemberSwapService(swapRepository, memberRepository, dependantRepository,
                memberNumberService, auditPublisher, eventPublisher, db);
        lenient().when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        lenient().when(eventPublisher.publishMemberChanged(
                any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any()))
                .thenReturn(Mono.empty());
    }

    // ------------------------------------------------------------------
    // request — forward-dated → PENDING
    // ------------------------------------------------------------------

    @Test
    void request_forwardDated_stashesPending_doesNotFlipMembership() {
        UUID memberId = UUID.randomUUID();
        UUID dependantId = UUID.randomUUID();
        Member member = new Member();
        member.setId(memberId);
        Dependant dep = new Dependant();
        dep.setId(dependantId);
        dep.setMemberId(memberId);
        dep.setStatus("active");

        when(memberRepository.findById(memberId)).thenReturn(Mono.just(member));
        when(dependantRepository.findById(dependantId)).thenReturn(Mono.just(dep));
        when(swapRepository.countLive(memberId, dependantId)).thenReturn(Mono.just(0L));
        when(swapRepository.save(any(MemberDependantSwap.class)))
                .thenAnswer(inv -> {
                    MemberDependantSwap r = inv.getArgument(0);
                    if (r.getId() == null) r.setId(UUID.randomUUID());
                    return Mono.just(r);
                });

        LocalDate effective = LocalDate.now().withDayOfMonth(1).plusMonths(1);
        var req = new MemberSwapRequest(dependantId, effective, "spouse takes over");

        StepVerifier.create(service.request(memberId, req,
                        UUID.randomUUID().toString(), "op@medfund.com"))
                .assertNext(saved -> {
                    assertThat(saved.getStatus()).isEqualTo("PENDING");
                    assertThat(saved.getEffectiveDate()).isEqualTo(effective);
                })
                .verifyComplete();

        verify(memberNumberService, never()).nextMemberNumber();
    }

    // ------------------------------------------------------------------
    // request — back-dated → APPLIED with atomic swap steps
    // ------------------------------------------------------------------

    @Test
    void request_backDated_appliesImmediately_promotesAndDemotesAndPublishesEvent() {
        UUID memberId = UUID.randomUUID();
        UUID dependantId = UUID.randomUUID();
        UUID schemeId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        Member oldMember = new Member();
        oldMember.setId(memberId);
        oldMember.setFirstName("Old");
        oldMember.setLastName("Principal");
        oldMember.setGroupId(groupId);
        oldMember.setSchemeId(schemeId);
        oldMember.setEnrollmentDate(LocalDate.now().withDayOfMonth(1).minusYears(1));
        Dependant dep = new Dependant();
        dep.setId(dependantId);
        dep.setMemberId(memberId);
        dep.setStatus("active");
        dep.setFirstName("New");
        dep.setLastName("Principal");

        when(memberRepository.findById(memberId)).thenReturn(Mono.just(oldMember));
        when(dependantRepository.findById(dependantId)).thenReturn(Mono.just(dep));
        when(swapRepository.countLive(memberId, dependantId)).thenReturn(Mono.just(0L));
        when(memberNumberService.nextMemberNumber()).thenReturn(Mono.just("MBR-999001"));
        when(memberNumberService.nextDependantNumber(any(Member.class)))
                .thenReturn(Mono.just("DEP-999002"));
        when(memberRepository.save(any(Member.class)))
                .thenAnswer(inv -> {
                    Member m = inv.getArgument(0);
                    if (m.getId() == null) m.setId(UUID.randomUUID());
                    return Mono.just(m);
                });
        when(dependantRepository.save(any(Dependant.class)))
                .thenAnswer(inv -> {
                    Dependant d = inv.getArgument(0);
                    if (d.getId() == null) d.setId(UUID.randomUUID());
                    return Mono.just(d);
                });
        when(swapRepository.save(any(MemberDependantSwap.class)))
                .thenAnswer(inv -> {
                    MemberDependantSwap r = inv.getArgument(0);
                    if (r.getId() == null) r.setId(UUID.randomUUID());
                    return Mono.just(r);
                });
        stubDbUpdate(1L);

        LocalDate effective = LocalDate.now().withDayOfMonth(1).minusMonths(2);
        var req = new MemberSwapRequest(dependantId, effective, "correction");

        StepVerifier.create(service.request(memberId, req,
                        UUID.randomUUID().toString(), "op@medfund.com"))
                .assertNext(saved -> {
                    assertThat(saved.getStatus()).isEqualTo("APPLIED");
                    assertThat(saved.getAppliedAt()).isNotNull();
                    assertThat(saved.getNewMemberId()).isNotNull();
                    assertThat(saved.getOldDependantId()).isNotNull();
                })
                .verifyComplete();

        // New member row promoted from dependant with inherited policy context.
        ArgumentCaptor<Member> newMember = ArgumentCaptor.forClass(Member.class);
        verify(memberRepository).save(newMember.capture());
        assertThat(newMember.getValue().getGroupId()).isEqualTo(groupId);
        assertThat(newMember.getValue().getSchemeId()).isEqualTo(schemeId);
        assertThat(newMember.getValue().getMemberNumber()).isEqualTo("MBR-999001");

        // New dependant row from old principal.
        ArgumentCaptor<Dependant> newDep = ArgumentCaptor.forClass(Dependant.class);
        verify(dependantRepository).save(newDep.capture());
        assertThat(newDep.getValue().getRelationship()).isEqualTo("SWAP_DEMOTED");
        assertThat(newDep.getValue().getMemberNumber()).isEqualTo("DEP-999002");

        // MEMBER_CHANGED event fires with SWAP_APPLIED + backdated=true.
        verify(eventPublisher).publishMemberChanged(
                any(), any(), eq("SWAP_APPLIED"), any(), any(), any(), eq(true), any(), any());
    }

    // ------------------------------------------------------------------
    // Validation
    // ------------------------------------------------------------------

    @Test
    void request_dependantBelongsToDifferentMember_errors() {
        UUID memberId = UUID.randomUUID();
        UUID dependantId = UUID.randomUUID();
        Member member = new Member();
        member.setId(memberId);
        Dependant dep = new Dependant();
        dep.setId(dependantId);
        dep.setMemberId(UUID.randomUUID()); // different parent
        dep.setStatus("active");
        when(memberRepository.findById(memberId)).thenReturn(Mono.just(member));
        when(dependantRepository.findById(dependantId)).thenReturn(Mono.just(dep));

        var req = new MemberSwapRequest(dependantId,
                LocalDate.now().withDayOfMonth(1).plusMonths(1), "wrong parent");

        StepVerifier.create(service.request(memberId, req,
                        UUID.randomUUID().toString(), "op@medfund.com"))
                .expectErrorSatisfies(t -> assertThat(t)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("belong"))
                .verify();
    }

    @Test
    void request_dependantTerminated_errors() {
        UUID memberId = UUID.randomUUID();
        UUID dependantId = UUID.randomUUID();
        Member member = new Member();
        member.setId(memberId);
        Dependant dep = new Dependant();
        dep.setId(dependantId);
        dep.setMemberId(memberId);
        dep.setStatus("terminated");
        when(memberRepository.findById(memberId)).thenReturn(Mono.just(member));
        when(dependantRepository.findById(dependantId)).thenReturn(Mono.just(dep));

        var req = new MemberSwapRequest(dependantId,
                LocalDate.now().withDayOfMonth(1).plusMonths(1), "trying");

        StepVerifier.create(service.request(memberId, req,
                        UUID.randomUUID().toString(), "op@medfund.com"))
                .expectErrorSatisfies(t -> assertThat(t)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("active or suspended"))
                .verify();
    }

    @Test
    void request_liveSwapExists_errors() {
        UUID memberId = UUID.randomUUID();
        UUID dependantId = UUID.randomUUID();
        Member member = new Member();
        member.setId(memberId);
        Dependant dep = new Dependant();
        dep.setId(dependantId);
        dep.setMemberId(memberId);
        dep.setStatus("active");
        when(memberRepository.findById(memberId)).thenReturn(Mono.just(member));
        when(dependantRepository.findById(dependantId)).thenReturn(Mono.just(dep));
        when(swapRepository.countLive(memberId, dependantId)).thenReturn(Mono.just(1L));

        var req = new MemberSwapRequest(dependantId,
                LocalDate.now().withDayOfMonth(1).plusMonths(1), "duplicate");

        StepVerifier.create(service.request(memberId, req,
                        UUID.randomUUID().toString(), "op@medfund.com"))
                .expectErrorSatisfies(t -> assertThat(t)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("live swap"))
                .verify();
    }

    // ------------------------------------------------------------------
    // approve / reject
    // ------------------------------------------------------------------

    @Test
    void approve_flipsToApproved() {
        UUID id = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        MemberDependantSwap row = new MemberDependantSwap();
        row.setId(id);
        row.setOldMemberId(UUID.randomUUID());
        row.setDependantId(UUID.randomUUID());
        row.setStatus("PENDING");

        when(swapRepository.findById(id)).thenReturn(Mono.just(row));
        when(swapRepository.save(any(MemberDependantSwap.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service.approve(id, actor.toString(), "boss@medfund.com"))
                .assertNext(saved -> {
                    assertThat(saved.getStatus()).isEqualTo("APPROVED");
                    assertThat(saved.getApprovedBy()).isEqualTo(actor);
                })
                .verifyComplete();
    }

    @Test
    void approve_nonPending_errors() {
        UUID id = UUID.randomUUID();
        MemberDependantSwap row = new MemberDependantSwap();
        row.setId(id);
        row.setOldMemberId(UUID.randomUUID());
        row.setDependantId(UUID.randomUUID());
        row.setStatus("APPROVED");
        when(swapRepository.findById(id)).thenReturn(Mono.just(row));

        StepVerifier.create(service.approve(id, UUID.randomUUID().toString(), "boss@medfund.com"))
                .expectErrorSatisfies(t -> assertThat(t)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("not PENDING"))
                .verify();
    }

    @Test
    void reject_capturesReason() {
        UUID id = UUID.randomUUID();
        MemberDependantSwap row = new MemberDependantSwap();
        row.setId(id);
        row.setOldMemberId(UUID.randomUUID());
        row.setDependantId(UUID.randomUUID());
        row.setStatus("PENDING");
        when(swapRepository.findById(id)).thenReturn(Mono.just(row));
        when(swapRepository.save(any(MemberDependantSwap.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service.reject(id, "wrong person",
                        UUID.randomUUID().toString(), "boss@medfund.com"))
                .assertNext(saved -> {
                    assertThat(saved.getStatus()).isEqualTo("REJECTED");
                    assertThat(saved.getRejectionReason()).isEqualTo("wrong person");
                })
                .verifyComplete();
    }

    // ------------------------------------------------------------------
    // apply — non-APPROVED guard
    // ------------------------------------------------------------------

    @Test
    void apply_nonApproved_errors() {
        UUID id = UUID.randomUUID();
        MemberDependantSwap row = new MemberDependantSwap();
        row.setId(id);
        row.setOldMemberId(UUID.randomUUID());
        row.setDependantId(UUID.randomUUID());
        row.setStatus("PENDING");
        when(swapRepository.findById(id)).thenReturn(Mono.just(row));

        StepVerifier.create(service.apply(id, UUID.randomUUID().toString(), "op@medfund.com"))
                .expectErrorSatisfies(t -> assertThat(t)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("not APPROVED"))
                .verify();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void stubDbUpdate(long rows) {
        DatabaseClient.GenericExecuteSpec spec = mock(DatabaseClient.GenericExecuteSpec.class);
        org.springframework.r2dbc.core.FetchSpec<java.util.Map<String, Object>> fetch =
                mock(org.springframework.r2dbc.core.FetchSpec.class);
        lenient().when(db.sql(anyString())).thenReturn(spec);
        lenient().when(spec.bind(anyString(), any())).thenReturn(spec);
        lenient().when(spec.fetch()).thenReturn(fetch);
        lenient().when(fetch.rowsUpdated()).thenReturn(Mono.just(rows));
    }
}

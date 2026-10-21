package com.medfund.user.status;

import com.medfund.shared.lifecycle.StatusTransitionRecorder;
import com.medfund.user.entity.Member;
import com.medfund.user.exception.MemberNotFoundException;
import com.medfund.user.repository.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentMatchers;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for the Phase 13 §A central write pathway (per L3).
 * Mocks the repository + recorder; transaction operator is stubbed to an
 * identity pass-through (real transactionality is proven by
 * MemberStatusTransitionIT against a real Postgres).
 */
@ExtendWith(MockitoExtension.class)
class MemberStatusTransitionServiceTest {

    @Mock private MemberRepository memberRepository;
    @Mock private StatusTransitionRecorder recorder;
    @Mock private TransactionalOperator tx;

    private MemberStatusTransitionService service;

    private final UUID memberId = UUID.randomUUID();
    private final UUID actorUuid = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // Identity pass-through — the chain under test runs unmodified.
        lenient().when(tx.transactional(ArgumentMatchers.<Mono<Member>>any()))
                .thenAnswer(inv -> inv.getArgument(0));
        service = new MemberStatusTransitionService(memberRepository, recorder, tx);
    }

    @Test
    void transition_activeToLapsed_writesHistoryRow_savesMember() {
        var member = memberWithStatus("active");
        when(memberRepository.findById(memberId)).thenReturn(Mono.just(member));
        when(recorder.recordMember(eq(memberId), eq("active"), eq("lapsed"), any(OffsetDateTime.class),
                eq(actorUuid), eq("actor@test.example"), eq("admin_lapse"), eq("grace window expired")))
                .thenReturn(Mono.empty());
        when(memberRepository.save(any(Member.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        // No explicit code → derived defaultReasonCodeFor("lapsed") == admin_lapse.
        StepVerifier.create(service.transition(memberId, "lapsed", null, "grace window expired",
                        actorUuid, "actor@test.example"))
                .assertNext(saved -> {
                    assertThat(saved.getStatus()).isEqualTo("lapsed");
                    // V043 suspend_reason maintained by the pathway.
                    assertThat(saved.getSuspendReason()).isEqualTo("grace window expired");
                    // Scheduled trio consumed.
                    assertThat(saved.getScheduledStatus()).isNull();
                })
                .verifyComplete();

        verify(recorder).recordMember(eq(memberId), eq("active"), eq("lapsed"), any(OffsetDateTime.class),
                eq(actorUuid), eq("actor@test.example"), eq("admin_lapse"), eq("grace window expired"));
        verify(memberRepository).save(any(Member.class));
    }

    @Test
    void transition_sameStatus_noHistoryRow_returnsExistingMember() {
        var member = memberWithStatus("lapsed");
        when(memberRepository.findById(memberId)).thenReturn(Mono.just(member));

        StepVerifier.create(service.transition(memberId, "lapsed", "admin_lapse", null,
                        actorUuid, "actor@test.example"))
                .expectNext(member)
                .verifyComplete();

        // Plan invariant #13: idempotent replay writes nothing.
        verifyNoInteractions(recorder);
        verify(memberRepository, never()).save(any());
    }

    @Test
    void transition_memberNotFound_propagatesTypedException() {
        when(memberRepository.findById(memberId)).thenReturn(Mono.empty());

        // Behaviour-preserving retrofit: MemberService.transitionStatus
        // historically surfaced MemberNotFoundException (404-mapped), not a
        // bare IllegalStateException — the pathway keeps that contract.
        StepVerifier.create(service.transition(memberId, "active", null, null,
                        actorUuid, "actor@test.example"))
                .expectError(MemberNotFoundException.class)
                .verify();

        verifyNoInteractions(recorder);
    }

    @Test
    void transition_recorderFails_entitySaveNeverHappens() {
        var member = memberWithStatus("active");
        when(memberRepository.findById(memberId)).thenReturn(Mono.just(member));
        when(recorder.recordMember(any(), anyString(), anyString(), any(), any(), anyString(), any(), any()))
                .thenReturn(Mono.error(new RuntimeException("history insert failed")));

        // Ordering guard: record → save inside one transaction. If save ran
        // before (or despite) a failed record, the entity would drift from
        // its history — exactly what invariant #13 forbids.
        StepVerifier.create(service.transition(memberId, "terminated", "auto_termination", null,
                        null, "rules-engine@insureflow"))
                .expectErrorMessage("history insert failed")
                .verify();

        verify(memberRepository, never()).save(any());
    }

    @Test
    void transition_actorEmailNull_isRejected() {
        StepVerifier.create(service.transition(memberId, "lapsed", null, null, actorUuid, null))
                .expectError(IllegalArgumentException.class)
                .verify();

        // Blank counts as missing too (feedback_audit_actor_email).
        StepVerifier.create(service.transition(memberId, "lapsed", null, null, actorUuid, "  "))
                .expectError(IllegalArgumentException.class)
                .verify();

        verifyNoInteractions(memberRepository, recorder);
    }

    @Test
    void defaultReasonCodeFor_mapsEveryLifecycleVocabArm() {
        assertThat(MemberStatusTransitionService.defaultReasonCodeFor("active")).isEqualTo("admin_activate");
        assertThat(MemberStatusTransitionService.defaultReasonCodeFor("suspended")).isEqualTo("admin_suspend");
        assertThat(MemberStatusTransitionService.defaultReasonCodeFor("terminated")).isEqualTo("admin_terminate");
        assertThat(MemberStatusTransitionService.defaultReasonCodeFor("deactivated")).isEqualTo("admin_deactivate");
        assertThat(MemberStatusTransitionService.defaultReasonCodeFor("lapsed")).isEqualTo("admin_lapse");
        // Anything outside the five lifecycle statuses falls back to 'other'
        // so the V112 CHECK never rejects an auto-derived row.
        assertThat(MemberStatusTransitionService.defaultReasonCodeFor("enrolled")).isEqualTo("other");
        assertThat(MemberStatusTransitionService.defaultReasonCodeFor(null)).isEqualTo("other");
    }

    private Member memberWithStatus(String status) {
        var m = new Member();
        m.setId(memberId);
        m.setMemberNumber("MBR-" + UUID.randomUUID().toString().substring(0, 8));
        m.setStatus(status);
        return m;
    }
}

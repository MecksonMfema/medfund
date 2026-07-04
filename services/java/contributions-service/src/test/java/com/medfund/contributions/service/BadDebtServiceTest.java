package com.medfund.contributions.service;

import com.medfund.contributions.entity.BadDebt;
import com.medfund.contributions.repository.BadDebtRepository;
import com.medfund.contributions.repository.ContributionRepository;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression guards for the write-off ledger contract. The bad_debts
 * table is the permanent risk record; the running balance is the current
 * collectable. writeOff and flagAndWriteOffAggregate MUST update both —
 * a regression that skips balanceService.writeOffBalance would leave
 * customers "owing" money that was written off.
 */
@ExtendWith(MockitoExtension.class)
class BadDebtServiceTest {

    @Mock BadDebtRepository badDebtRepository;
    @Mock ContributionRepository contributionRepository;
    @Mock AuditPublisher auditPublisher;
    @Mock BalanceService balanceService;

    private BadDebtService service;

    @BeforeEach
    void setUp() {
        service = new BadDebtService(badDebtRepository, contributionRepository,
                auditPublisher, balanceService);
        lenient().when(auditPublisher.publish(any(AuditEvent.class))).thenReturn(Mono.empty());
        lenient().when(balanceService.writeOffBalance(any(), any(), any(), any()))
                .thenReturn(Mono.empty());
    }

    @Test
    void writeOff_flaggedRow_stampsWrittenOff_andZerosBalance() {
        UUID id = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        BadDebt bd = new BadDebt();
        bd.setId(id);
        bd.setMemberId(memberId);
        bd.setGroupId(groupId);
        bd.setContributionId(UUID.randomUUID());
        bd.setAmount(new BigDecimal("125.00"));
        bd.setCurrencyCode("USD");
        bd.setStatus("FLAGGED");
        when(badDebtRepository.findById(id)).thenReturn(Mono.just(bd));
        when(badDebtRepository.save(any(BadDebt.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        String actorId = UUID.randomUUID().toString();

        StepVerifier.create(service.writeOff(id, actorId, "actor@test")
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "tnt")))
                .assertNext(saved -> {
                    assertThat(saved.getStatus()).isEqualTo("WRITTEN_OFF");
                    assertThat(saved.getWrittenOffDate()).isNotNull();
                })
                .verifyComplete();

        // The load-bearing pairing: bad_debts.status → WRITTEN_OFF AND
        // balance is zeroed. Args must match the bad_debts row's fields
        // exactly — a swap of memberId/groupId here routes the ledger
        // credit to the wrong subject.
        verify(balanceService).writeOffBalance(
                eq(memberId), eq(groupId), eq("USD"), eq(new BigDecimal("125.00")));
    }

    @Test
    void writeOff_notFlagged_errorsBeforeMutating() {
        UUID id = UUID.randomUUID();
        BadDebt bd = new BadDebt();
        bd.setId(id);
        bd.setStatus("WRITTEN_OFF"); // already done
        when(badDebtRepository.findById(id)).thenReturn(Mono.just(bd));

        StepVerifier.create(service.writeOff(id, UUID.randomUUID().toString(), "actor@test"))
                .expectError(IllegalStateException.class)
                .verify();

        // No double-write on the balance for an already-written-off row.
        verify(balanceService, never()).writeOffBalance(any(), any(), any(), any());
        verify(badDebtRepository, never()).save(any());
    }

    @Test
    void flagAndWriteOffAggregate_insertsWrittenOffRow_andZerosBalance() {
        // Auto-write-off from the arrears sweep: no contribution_id
        // (aggregate across many), status skips FLAGGED and goes straight
        // to WRITTEN_OFF. Both must land in one transactional step.
        UUID groupId = UUID.randomUUID();
        when(badDebtRepository.save(any(BadDebt.class)))
                .thenAnswer(inv -> {
                    BadDebt b = inv.getArgument(0);
                    if (b.getId() == null) b.setId(UUID.randomUUID());
                    return Mono.just(b);
                });

        StepVerifier.create(service.flagAndWriteOffAggregate(
                        "GROUP", groupId, "USD", new BigDecimal("500.00"),
                        "AUTO_ARREARS_WRITE_OFF",
                        com.medfund.shared.audit.AuditActor.SYSTEM_ID,
                        com.medfund.shared.audit.AuditActor.SYSTEM_EMAIL)
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "tnt")))
                .assertNext(saved -> {
                    assertThat(saved.getStatus()).isEqualTo("WRITTEN_OFF");
                    assertThat(saved.getGroupId()).isEqualTo(groupId);
                    // subjectType=GROUP → memberId elided so the FK doesn't
                    // point at an unrelated member row.
                    assertThat(saved.getMemberId()).isNull();
                    // contribution_id NULL — aggregate row, not per-contribution.
                    assertThat(saved.getContributionId()).isNull();
                    assertThat(saved.getReason()).isEqualTo("AUTO_ARREARS_WRITE_OFF");
                    // flagged_date + written_off_date both stamped in
                    // one step (skip FLAGGED intermediate).
                    assertThat(saved.getFlaggedDate()).isNotNull();
                    assertThat(saved.getWrittenOffDate()).isNotNull();
                })
                .verifyComplete();

        // Ledger zero for the group leg only.
        verify(balanceService).writeOffBalance(
                eq(null), eq(groupId), eq("USD"), eq(new BigDecimal("500.00")));
    }

    @Test
    void flagAndWriteOffAggregate_memberSubjectType_routesToMemberFk() {
        UUID memberId = UUID.randomUUID();
        when(badDebtRepository.save(any(BadDebt.class)))
                .thenAnswer(inv -> {
                    BadDebt b = inv.getArgument(0);
                    if (b.getId() == null) b.setId(UUID.randomUUID());
                    return Mono.just(b);
                });

        StepVerifier.create(service.flagAndWriteOffAggregate(
                        "MEMBER", memberId, "USD", new BigDecimal("60.00"),
                        "AUTO_ARREARS_WRITE_OFF",
                        com.medfund.shared.audit.AuditActor.SYSTEM_ID,
                        com.medfund.shared.audit.AuditActor.SYSTEM_EMAIL)
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "tnt")))
                .assertNext(saved -> {
                    assertThat(saved.getMemberId()).isEqualTo(memberId);
                    assertThat(saved.getGroupId()).isNull();
                })
                .verifyComplete();

        verify(balanceService).writeOffBalance(
                eq(memberId), eq(null), eq("USD"), eq(new BigDecimal("60.00")));
    }

    @Test
    void flagAndWriteOffAggregate_nullSubject_isNoOp() {
        // Guard against a caller passing garbage — no row inserted,
        // no ledger touched.
        StepVerifier.create(service.flagAndWriteOffAggregate(
                        "MEMBER", null, "USD", new BigDecimal("10.00"),
                        "AUTO_ARREARS_WRITE_OFF", "sys", "sys@x"))
                .verifyComplete();

        verify(badDebtRepository, never()).save(any());
        verify(balanceService, never()).writeOffBalance(any(), any(), any(), any());
    }

    @Test
    void flagAndWriteOffAggregate_preservesSubjectFkForRiskScoring() {
        // Documentation-as-test: the whole point of the bad_debts row is
        // to survive as a permanent record. This test asserts that the
        // inserted row carries the subject FK (not just an amount memo)
        // so a later "risk score for group X" query can find it.
        UUID groupId = UUID.randomUUID();
        ArgumentCaptor<BadDebt> cap = ArgumentCaptor.forClass(BadDebt.class);
        when(badDebtRepository.save(cap.capture())).thenAnswer(inv -> {
            BadDebt b = inv.getArgument(0);
            if (b.getId() == null) b.setId(UUID.randomUUID());
            return Mono.just(b);
        });

        service.flagAndWriteOffAggregate("GROUP", groupId, "USD",
                        new BigDecimal("999.99"), "AUTO_ARREARS_WRITE_OFF",
                        com.medfund.shared.audit.AuditActor.SYSTEM_ID,
                        com.medfund.shared.audit.AuditActor.SYSTEM_EMAIL)
                .contextWrite(ctx -> ctx.put("TENANT_ID", "tnt"))
                .block();

        BadDebt inserted = cap.getValue();
        assertThat(inserted.getGroupId()).isEqualTo(groupId);
        assertThat(inserted.getAmount()).isEqualByComparingTo("999.99");
        assertThat(inserted.getCurrencyCode()).isEqualTo("USD");
        assertThat(inserted.getStatus()).isEqualTo("WRITTEN_OFF");
    }
}

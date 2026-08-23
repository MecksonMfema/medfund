package com.medfund.finance.producer.service;

import com.medfund.finance.producer.dto.CreateAdjustmentRequest;
import com.medfund.finance.producer.entity.CommissionAdjustment;
import com.medfund.finance.producer.entity.CommissionTransaction;
import com.medfund.finance.producer.repository.CommissionAdjustmentRepository;
import com.medfund.finance.producer.repository.CommissionTransactionRepository;
import com.medfund.finance.producer.util.ReferenceGenerator;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CommissionAdjustmentService} — pure Mockito, no
 * Postgres. Covers every state-transition branch + four-eyes invariant +
 * audit event shape.
 */
@ExtendWith(MockitoExtension.class)
class CommissionAdjustmentServiceTest {

    private static final String DRAFTER_ID    = "aaaaaaaa-0000-4000-8000-000000000001";
    private static final String DRAFTER_EMAIL = "drafter@medfund";
    private static final String APPROVER_ID   = "bbbbbbbb-0000-4000-8000-000000000002";
    private static final String APPROVER_EMAIL = "approver@medfund";
    private static final String JUSTIFICATION = "Legitimate reason with plenty of detail to explain why";

    @Mock CommissionAdjustmentRepository adjustmentRepository;
    @Mock CommissionTransactionRepository commissionTxnRepository;
    @Mock ReferenceGenerator referenceGenerator;
    @Mock AuditPublisher auditPublisher;

    @InjectMocks CommissionAdjustmentService service;

    // ── createDraft ────────────────────────────────────────────────────────

    @Test
    void createDraft_happyPath_writesRowAndAudits() {
        UUID targetId = UUID.randomUUID();
        CommissionTransaction target = target(targetId, "USD");

        when(commissionTxnRepository.findById(targetId)).thenReturn(Mono.just(target));
        when(referenceGenerator.nextAdjustmentReference()).thenReturn(Mono.just("COMM-ADJ-2026-000001"));
        when(adjustmentRepository.save(any())).thenAnswer(inv -> {
            CommissionAdjustment a = inv.getArgument(0);
            a.setId(UUID.randomUUID());
            return Mono.just(a);
        });
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        CreateAdjustmentRequest req = new CreateAdjustmentRequest(
                targetId, "EX_GRATIA", new BigDecimal("125.00"), JUSTIFICATION);

        StepVerifier.create(service.createDraft(req, DRAFTER_ID, DRAFTER_EMAIL))
                .assertNext(resp -> {
                    assertThat(resp.status()).isEqualTo("DRAFT");
                    assertThat(resp.reference()).isEqualTo("COMM-ADJ-2026-000001");
                    assertThat(resp.nativeCurrency()).isEqualTo("USD");
                    assertThat(resp.adjustmentAmount()).isEqualByComparingTo("125.00");
                    assertThat(resp.adjustmentType()).isEqualTo("EX_GRATIA");
                    assertThat(resp.actorId().toString()).isEqualTo(DRAFTER_ID);
                    assertThat(resp.actorEmail()).isEqualTo(DRAFTER_EMAIL);
                })
                .verifyComplete();

        ArgumentCaptor<AuditEvent> cap = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher, times(1)).publish(cap.capture());
        assertThat(cap.getValue().entityType()).isEqualTo("CommissionAdjustment");
        assertThat(cap.getValue().action()).isEqualTo("CREATE");
        assertThat(cap.getValue().entityName()).isEqualTo("COMM-ADJ-2026-000001");
        assertThat(cap.getValue().entityName()).doesNotStartWith(cap.getValue().entityId());
        assertThat(cap.getValue().actorEmail()).isEqualTo(DRAFTER_EMAIL);
    }

    @Test
    void createDraft_missingTarget_isBadRequest() {
        UUID targetId = UUID.randomUUID();
        when(commissionTxnRepository.findById(targetId)).thenReturn(Mono.empty());

        CreateAdjustmentRequest req = new CreateAdjustmentRequest(
                targetId, "EX_GRATIA", new BigDecimal("10.00"), JUSTIFICATION);

        StepVerifier.create(service.createDraft(req, DRAFTER_ID, DRAFTER_EMAIL))
                .expectError(IllegalArgumentException.class)
                .verify();

        verify(adjustmentRepository, never()).save(any());
    }

    @Test
    void createDraft_shortJustification_isBadRequest() {
        CreateAdjustmentRequest req = new CreateAdjustmentRequest(
                UUID.randomUUID(), "EX_GRATIA", new BigDecimal("1.00"), "too short");

        StepVerifier.create(service.createDraft(req, DRAFTER_ID, DRAFTER_EMAIL))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(IllegalArgumentException.class);
                    assertThat(err.getMessage()).contains("20 characters");
                })
                .verify();

        verify(commissionTxnRepository, never()).findById(any(UUID.class));
        verify(adjustmentRepository, never()).save(any());
    }

    @Test
    void createDraft_zeroAmount_isBadRequest() {
        CreateAdjustmentRequest req = new CreateAdjustmentRequest(
                UUID.randomUUID(), "EX_GRATIA", BigDecimal.ZERO, JUSTIFICATION);

        StepVerifier.create(service.createDraft(req, DRAFTER_ID, DRAFTER_EMAIL))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(IllegalArgumentException.class);
                    assertThat(err.getMessage()).contains("non-zero");
                })
                .verify();

        verify(commissionTxnRepository, never()).findById(any(UUID.class));
    }

    @Test
    void createDraft_missingType_isBadRequest() {
        CreateAdjustmentRequest req = new CreateAdjustmentRequest(
                UUID.randomUUID(), null, new BigDecimal("10.00"), JUSTIFICATION);

        StepVerifier.create(service.createDraft(req, DRAFTER_ID, DRAFTER_EMAIL))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    void createDraft_referenceGenerator_carriedIntoAuditEntityName() {
        UUID targetId = UUID.randomUUID();
        CommissionTransaction target = target(targetId, "USD");
        when(commissionTxnRepository.findById(targetId)).thenReturn(Mono.just(target));
        when(referenceGenerator.nextAdjustmentReference()).thenReturn(Mono.just("COMM-ADJ-2026-000042"));
        when(adjustmentRepository.save(any())).thenAnswer(inv -> {
            CommissionAdjustment a = inv.getArgument(0);
            a.setId(UUID.randomUUID());
            return Mono.just(a);
        });
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        service.createDraft(new CreateAdjustmentRequest(
                        targetId, "MANUAL_REVERSAL", new BigDecimal("-50.00"), JUSTIFICATION),
                DRAFTER_ID, DRAFTER_EMAIL).block();

        ArgumentCaptor<AuditEvent> cap = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher).publish(cap.capture());
        assertThat(cap.getValue().entityName()).isEqualTo("COMM-ADJ-2026-000042");
    }

    // ── approve ────────────────────────────────────────────────────────────

    @Test
    void approve_happyPath_flipsToApprovedAndAudits() {
        UUID id = UUID.randomUUID();
        CommissionAdjustment existing = draftAdjustment(id, "USD", DRAFTER_ID);
        when(adjustmentRepository.findById(id)).thenReturn(Mono.just(existing));
        when(adjustmentRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(service.approve(id, APPROVER_ID, APPROVER_EMAIL))
                .assertNext(resp -> {
                    assertThat(resp.status()).isEqualTo("APPROVED");
                    assertThat(resp.approverActorId().toString()).isEqualTo(APPROVER_ID);
                    assertThat(resp.approverActorEmail()).isEqualTo(APPROVER_EMAIL);
                    assertThat(resp.approvedAt()).isNotNull();
                })
                .verifyComplete();

        ArgumentCaptor<AuditEvent> cap = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher).publish(cap.capture());
        assertThat(cap.getValue().action()).isEqualTo("APPROVE");
    }

    @Test
    void approve_notDraft_isConflict() {
        UUID id = UUID.randomUUID();
        CommissionAdjustment existing = draftAdjustment(id, "USD", DRAFTER_ID);
        existing.setStatus("APPROVED");
        when(adjustmentRepository.findById(id)).thenReturn(Mono.just(existing));

        StepVerifier.create(service.approve(id, APPROVER_ID, APPROVER_EMAIL))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(IllegalStateException.class);
                    assertThat(err.getMessage()).contains("Only DRAFT");
                })
                .verify();

        verify(adjustmentRepository, never()).save(any());
    }

    @Test
    void approve_sameActor_isFourEyesViolation() {
        UUID id = UUID.randomUUID();
        CommissionAdjustment existing = draftAdjustment(id, "USD", DRAFTER_ID);
        when(adjustmentRepository.findById(id)).thenReturn(Mono.just(existing));

        StepVerifier.create(service.approve(id, DRAFTER_ID, DRAFTER_EMAIL))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(IllegalStateException.class);
                    assertThat(err.getMessage()).contains("four-eyes");
                })
                .verify();

        verify(adjustmentRepository, never()).save(any());
    }

    // ── commit ─────────────────────────────────────────────────────────────

    @Test
    void commit_happyPath_writesCompensatingTransactionAndLinks() {
        UUID id = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        CommissionAdjustment existing = draftAdjustment(id, "USD", DRAFTER_ID);
        existing.setStatus("APPROVED");
        existing.setTargetCommissionTransactionId(targetId);
        existing.setAdjustmentAmount(new BigDecimal("125.00"));

        CommissionTransaction target = target(targetId, "USD");

        when(adjustmentRepository.findById(id)).thenReturn(Mono.just(existing));
        when(commissionTxnRepository.findById(targetId)).thenReturn(Mono.just(target));
        when(referenceGenerator.nextCommissionReference()).thenReturn(Mono.just("COMM-2026-000099"));
        when(commissionTxnRepository.save(any())).thenAnswer(inv -> {
            CommissionTransaction ct = inv.getArgument(0);
            ct.setId(UUID.randomUUID());
            return Mono.just(ct);
        });
        when(adjustmentRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(service.commit(id, APPROVER_ID, APPROVER_EMAIL))
                .assertNext(resp -> {
                    assertThat(resp.status()).isEqualTo("COMMITTED");
                    assertThat(resp.committedAt()).isNotNull();
                    assertThat(resp.committedTxnId()).isNotNull();
                })
                .verifyComplete();

        ArgumentCaptor<CommissionTransaction> ct = ArgumentCaptor.forClass(CommissionTransaction.class);
        verify(commissionTxnRepository).save(ct.capture());
        assertThat(ct.getValue().getReversalOfTxnId()).isEqualTo(targetId);
        assertThat(ct.getValue().getNativeAmount()).isEqualByComparingTo("125.00");
        assertThat(ct.getValue().getNativeCurrency()).isEqualTo("USD");
        assertThat(ct.getValue().getStatus()).isEqualTo("ACCRUED");
        assertThat(ct.getValue().getReference()).isEqualTo("COMM-2026-000099");
        assertThat(ct.getValue().getProducerId()).isEqualTo(target.getProducerId());

        // Two audits: compensating-txn CREATE, adjustment COMMIT
        ArgumentCaptor<AuditEvent> audits = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher, times(2)).publish(audits.capture());
        assertThat(audits.getAllValues()).extracting(AuditEvent::action)
                .contains("CREATE", "COMMIT");
    }

    @Test
    void commit_notApproved_isConflict() {
        UUID id = UUID.randomUUID();
        CommissionAdjustment existing = draftAdjustment(id, "USD", DRAFTER_ID);
        when(adjustmentRepository.findById(id)).thenReturn(Mono.just(existing));

        StepVerifier.create(service.commit(id, APPROVER_ID, APPROVER_EMAIL))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(IllegalStateException.class);
                    assertThat(err.getMessage()).contains("Only APPROVED");
                })
                .verify();

        verify(commissionTxnRepository, never()).save(any());
    }

    @Test
    void commit_compensatingHasCorrectSignedAmount() {
        UUID id = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        CommissionAdjustment existing = draftAdjustment(id, "USD", DRAFTER_ID);
        existing.setStatus("APPROVED");
        existing.setTargetCommissionTransactionId(targetId);
        existing.setAdjustmentAmount(new BigDecimal("-50.00"));   // reversal

        when(adjustmentRepository.findById(id)).thenReturn(Mono.just(existing));
        when(commissionTxnRepository.findById(targetId)).thenReturn(Mono.just(target(targetId, "USD")));
        when(referenceGenerator.nextCommissionReference()).thenReturn(Mono.just("COMM-2026-000100"));
        when(commissionTxnRepository.save(any())).thenAnswer(inv -> {
            CommissionTransaction ct = inv.getArgument(0);
            ct.setId(UUID.randomUUID());
            return Mono.just(ct);
        });
        when(adjustmentRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        service.commit(id, APPROVER_ID, APPROVER_EMAIL).block();

        ArgumentCaptor<CommissionTransaction> ct = ArgumentCaptor.forClass(CommissionTransaction.class);
        verify(commissionTxnRepository).save(ct.capture());
        assertThat(ct.getValue().getNativeAmount()).isEqualByComparingTo("-50.00");
    }

    @Test
    void commit_isTerminal_secondCommitRejected() {
        UUID id = UUID.randomUUID();
        CommissionAdjustment existing = draftAdjustment(id, "USD", DRAFTER_ID);
        existing.setStatus("COMMITTED");
        when(adjustmentRepository.findById(id)).thenReturn(Mono.just(existing));

        StepVerifier.create(service.commit(id, APPROVER_ID, APPROVER_EMAIL))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(IllegalStateException.class);
                    assertThat(err.getMessage()).contains("Only APPROVED");
                })
                .verify();
    }

    // ── void ───────────────────────────────────────────────────────────────

    @Test
    void voidAdjustment_fromDraft_happyPath() {
        UUID id = UUID.randomUUID();
        CommissionAdjustment existing = draftAdjustment(id, "USD", DRAFTER_ID);
        when(adjustmentRepository.findById(id)).thenReturn(Mono.just(existing));
        when(adjustmentRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(service.voidAdjustment(id, "no longer needed",
                        APPROVER_ID, APPROVER_EMAIL))
                .assertNext(resp -> {
                    assertThat(resp.status()).isEqualTo("VOIDED");
                    assertThat(resp.voidedReason()).isEqualTo("no longer needed");
                    assertThat(resp.voidedAt()).isNotNull();
                })
                .verifyComplete();

        ArgumentCaptor<AuditEvent> cap = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher).publish(cap.capture());
        assertThat(cap.getValue().action()).isEqualTo("VOID");
    }

    @Test
    void voidAdjustment_fromApproved_happyPath() {
        UUID id = UUID.randomUUID();
        CommissionAdjustment existing = draftAdjustment(id, "USD", DRAFTER_ID);
        existing.setStatus("APPROVED");
        when(adjustmentRepository.findById(id)).thenReturn(Mono.just(existing));
        when(adjustmentRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(service.voidAdjustment(id, "changed mind",
                        APPROVER_ID, APPROVER_EMAIL))
                .assertNext(resp -> assertThat(resp.status()).isEqualTo("VOIDED"))
                .verifyComplete();
    }

    @Test
    void voidAdjustment_fromCommitted_isRejected() {
        UUID id = UUID.randomUUID();
        CommissionAdjustment existing = draftAdjustment(id, "USD", DRAFTER_ID);
        existing.setStatus("COMMITTED");
        when(adjustmentRepository.findById(id)).thenReturn(Mono.just(existing));

        StepVerifier.create(service.voidAdjustment(id, "too late", APPROVER_ID, APPROVER_EMAIL))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(IllegalStateException.class);
                    assertThat(err.getMessage()).contains("Cannot void a COMMITTED");
                })
                .verify();

        verify(adjustmentRepository, never()).save(any());
    }

    @Test
    void voidAdjustment_missingReason_isRejected() {
        UUID id = UUID.randomUUID();
        StepVerifier.create(service.voidAdjustment(id, "  ", APPROVER_ID, APPROVER_EMAIL))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(IllegalArgumentException.class);
                    assertThat(err.getMessage()).contains("reason");
                })
                .verify();

        verify(adjustmentRepository, never()).findById(any(UUID.class));
    }

    // ── queue ──────────────────────────────────────────────────────────────

    @Test
    void queue_defaultsToDraftPlusApproved() {
        when(adjustmentRepository.findByStatusInOrderByCreatedAtAsc(any(), any()))
                .thenReturn(reactor.core.publisher.Flux.empty());

        StepVerifier.create(service.queue(null, 0, 50))
                .verifyComplete();

        ArgumentCaptor<java.util.Collection<String>> statuses =
                ArgumentCaptor.forClass(java.util.Collection.class);
        verify(adjustmentRepository).findByStatusInOrderByCreatedAtAsc(statuses.capture(), any());
        assertThat(statuses.getValue()).containsExactly("DRAFT", "APPROVED");
    }

    @Test
    void audit_entityName_isReferenceNotUuid() {
        UUID id = UUID.randomUUID();
        CommissionAdjustment existing = draftAdjustment(id, "USD", DRAFTER_ID);
        existing.setReference("COMM-ADJ-2026-000123");
        when(adjustmentRepository.findById(id)).thenReturn(Mono.just(existing));
        when(adjustmentRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        service.approve(id, APPROVER_ID, APPROVER_EMAIL).block();

        ArgumentCaptor<AuditEvent> cap = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher).publish(cap.capture());
        assertThat(cap.getValue().entityName()).isEqualTo("COMM-ADJ-2026-000123");
        assertThat(cap.getValue().entityName()).isNotEqualTo(id.toString());
    }

    // ── fixtures ───────────────────────────────────────────────────────────

    private CommissionTransaction target(UUID id, String currency) {
        CommissionTransaction c = new CommissionTransaction();
        c.setId(id);
        c.setProducerId(UUID.randomUUID());
        c.setContributionId(UUID.randomUUID());
        c.setMemberId(UUID.randomUUID());
        c.setInsuranceLine("HEALTH");
        c.setNativeAmount(new BigDecimal("500.00"));
        c.setNativeCurrency(currency);
        c.setContributionAmount(new BigDecimal("10000.00"));
        c.setAppliedRatePct(new BigDecimal("5.00"));
        c.setStatus("PAID");
        return c;
    }

    private CommissionAdjustment draftAdjustment(UUID id, String currency, String drafterId) {
        CommissionAdjustment a = new CommissionAdjustment();
        a.setId(id);
        a.setReference("COMM-ADJ-2026-000001");
        a.setTargetCommissionTransactionId(UUID.randomUUID());
        a.setAdjustmentType("EX_GRATIA");
        a.setAdjustmentAmount(new BigDecimal("100.00"));
        a.setNativeCurrency(currency);
        a.setJustification(JUSTIFICATION);
        a.setStatus("DRAFT");
        a.setActorId(UUID.fromString(drafterId));
        a.setActorEmail(DRAFTER_EMAIL);
        return a;
    }
}

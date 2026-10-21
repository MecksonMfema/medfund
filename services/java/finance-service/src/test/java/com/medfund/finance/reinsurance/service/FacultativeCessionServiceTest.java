package com.medfund.finance.reinsurance.service;

import com.medfund.finance.reinsurance.dto.CreateFacultativeCessionRequest;
import com.medfund.finance.reinsurance.entity.Cession;
import com.medfund.finance.reinsurance.entity.Recovery;
import com.medfund.finance.reinsurance.entity.Treaty;
import com.medfund.finance.reinsurance.entity.TreatyApplicableLine;
import com.medfund.finance.reinsurance.repository.CessionRepository;
import com.medfund.finance.reinsurance.repository.RecoveryRepository;
import com.medfund.finance.reinsurance.repository.TreatyApplicableLineRepository;
import com.medfund.finance.reinsurance.repository.TreatyRepository;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FacultativeCessionService} — pure Mockito, no
 * Postgres. Every branch of every transition is exercised against a
 * canned entity graph.
 */
@ExtendWith(MockitoExtension.class)
class FacultativeCessionServiceTest {

    private static final String ACTOR_ID    = "aaaaaaaa-0000-4000-8000-000000000001";
    private static final String ACTOR_EMAIL = "underwriter@medfund";

    @Mock CessionRepository cessionRepository;
    @Mock RecoveryRepository recoveryRepository;
    @Mock TreatyRepository treatyRepository;
    @Mock TreatyApplicableLineRepository applicableLineRepository;
    @Mock AuditPublisher auditPublisher;

    @InjectMocks FacultativeCessionService service;

    // ── createDraft ────────────────────────────────────────────────────────

    @Test
    void createDraft_activeTreatyAndCoveredLine_writesDraftAndAudits() {
        UUID treatyId = UUID.randomUUID();
        UUID claimId  = UUID.randomUUID();
        Treaty treaty = activeTreaty(treatyId);

        when(treatyRepository.findById(treatyId)).thenReturn(Mono.just(treaty));
        when(applicableLineRepository.findByTreatyId(treatyId))
                .thenReturn(Flux.just(applicableLine(treatyId, "HEALTH")));
        when(cessionRepository.findLiveLossByClaimAndTreaty(claimId, treatyId))
                .thenReturn(Mono.empty());
        when(cessionRepository.save(any())).thenAnswer(inv -> {
            Cession c = inv.getArgument(0);
            c.setId(UUID.randomUUID());
            return Mono.just(c);
        });
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        CreateFacultativeCessionRequest req = request(claimId, treatyId, "5000", "10000");

        StepVerifier.create(service.createDraft(req, "HEALTH", ACTOR_ID, ACTOR_EMAIL))
                .assertNext(resp -> {
                    assertThat(resp.status()).isEqualTo("DRAFT");
                    assertThat(resp.source()).isEqualTo("FACULTATIVE");
                    assertThat(resp.cessionType()).isEqualTo("LOSS");
                    assertThat(resp.treatyId()).isEqualTo(treatyId);
                    assertThat(resp.sourceEventId()).isEqualTo(claimId);
                    assertThat(resp.sourceEventType()).isEqualTo("CLAIM_FACULTATIVE");
                    assertThat(resp.cededAmount()).isEqualByComparingTo("5000");
                    assertThat(resp.basisAmount()).isEqualByComparingTo("10000");
                    assertThat(resp.currencyCode()).isEqualTo("USD");
                })
                .verifyComplete();

        ArgumentCaptor<AuditEvent> ac = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher, times(1)).publish(ac.capture());
        assertThat(ac.getValue().entityType()).isEqualTo("Cession");
        assertThat(ac.getValue().action()).isEqualTo("CREATE");
        assertThat(ac.getValue().entityName()).doesNotStartWith(ac.getValue().entityId());
        verify(recoveryRepository, never()).save(any());
    }

    @Test
    void createDraft_treatyNotFound_isBadRequest() {
        UUID treatyId = UUID.randomUUID();
        when(treatyRepository.findById(treatyId)).thenReturn(Mono.empty());

        StepVerifier.create(service.createDraft(
                        request(UUID.randomUUID(), treatyId, "5000", "10000"),
                        "HEALTH", ACTOR_ID, ACTOR_EMAIL))
                .expectError(IllegalArgumentException.class)
                .verify();

        verify(cessionRepository, never()).save(any());
    }

    @Test
    void createDraft_treatyNotActive_isConflict() {
        UUID treatyId = UUID.randomUUID();
        Treaty draft = activeTreaty(treatyId);
        draft.setStatus("DRAFT");
        when(treatyRepository.findById(treatyId)).thenReturn(Mono.just(draft));

        StepVerifier.create(service.createDraft(
                        request(UUID.randomUUID(), treatyId, "5000", "10000"),
                        "HEALTH", ACTOR_ID, ACTOR_EMAIL))
                .expectError(IllegalStateException.class)
                .verify();

        verify(applicableLineRepository, never()).findByTreatyId(any());
    }

    @Test
    void createDraft_lineNotCovered_isConflict() {
        UUID treatyId = UUID.randomUUID();
        Treaty treaty = activeTreaty(treatyId);
        when(treatyRepository.findById(treatyId)).thenReturn(Mono.just(treaty));
        when(applicableLineRepository.findByTreatyId(treatyId))
                .thenReturn(Flux.just(applicableLine(treatyId, "LIFE")));

        StepVerifier.create(service.createDraft(
                        request(UUID.randomUUID(), treatyId, "5000", "10000"),
                        "HEALTH", ACTOR_ID, ACTOR_EMAIL))
                .expectError(IllegalStateException.class)
                .verify();

        verify(cessionRepository, never()).findLiveLossByClaimAndTreaty(any(), any());
        verify(cessionRepository, never()).save(any());
    }

    @Test
    void createDraft_existingLiveCession_isConflict() {
        UUID treatyId = UUID.randomUUID();
        UUID claimId  = UUID.randomUUID();
        Treaty treaty = activeTreaty(treatyId);
        Cession existing = new Cession();
        existing.setId(UUID.randomUUID());
        existing.setStatus("APPROVED");

        when(treatyRepository.findById(treatyId)).thenReturn(Mono.just(treaty));
        when(applicableLineRepository.findByTreatyId(treatyId))
                .thenReturn(Flux.just(applicableLine(treatyId, "HEALTH")));
        when(cessionRepository.findLiveLossByClaimAndTreaty(claimId, treatyId))
                .thenReturn(Mono.just(existing));

        StepVerifier.create(service.createDraft(
                        request(claimId, treatyId, "5000", "10000"),
                        "HEALTH", ACTOR_ID, ACTOR_EMAIL))
                .expectError(IllegalStateException.class)
                .verify();

        verify(cessionRepository, never()).save(any());
    }

    @Test
    void createDraft_uniqueViolationOnRace_isConflict() {
        UUID treatyId = UUID.randomUUID();
        UUID claimId  = UUID.randomUUID();
        Treaty treaty = activeTreaty(treatyId);

        when(treatyRepository.findById(treatyId)).thenReturn(Mono.just(treaty));
        when(applicableLineRepository.findByTreatyId(treatyId))
                .thenReturn(Flux.just(applicableLine(treatyId, "HEALTH")));
        when(cessionRepository.findLiveLossByClaimAndTreaty(claimId, treatyId))
                .thenReturn(Mono.empty());
        when(cessionRepository.save(any()))
                .thenReturn(Mono.error(new DuplicateKeyException("ux_cession_source_event")));

        StepVerifier.create(service.createDraft(
                        request(claimId, treatyId, "5000", "10000"),
                        "HEALTH", ACTOR_ID, ACTOR_EMAIL))
                .expectError(IllegalStateException.class)
                .verify();

        verify(auditPublisher, never()).publish(any());
    }

    @Test
    void createDraft_missingInsuranceLine_isBadRequest() {
        StepVerifier.create(service.createDraft(
                        request(UUID.randomUUID(), UUID.randomUUID(), "5000", "10000"),
                        null, ACTOR_ID, ACTOR_EMAIL))
                .expectError(IllegalArgumentException.class)
                .verify();

        verify(treatyRepository, never()).findById(any(UUID.class));
    }

    // ── approve ────────────────────────────────────────────────────────────

    @Test
    void approve_draftFacultative_transitionsAndAudits() {
        UUID cessionId = UUID.randomUUID();
        Cession draft = facultativeCession(cessionId, "DRAFT");
        when(cessionRepository.findById(cessionId)).thenReturn(Mono.just(draft));
        when(cessionRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(service.approve(cessionId, ACTOR_ID, ACTOR_EMAIL))
                .assertNext(resp -> {
                    assertThat(resp.status()).isEqualTo("APPROVED");
                    assertThat(resp.source()).isEqualTo("FACULTATIVE");
                })
                .verifyComplete();

        ArgumentCaptor<AuditEvent> ac = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher).publish(ac.capture());
        assertThat(ac.getValue().action()).isEqualTo("APPROVE");
        assertThat(ac.getValue().changedFields()).contains("status");
    }

    @Test
    void approve_nonFacultative_isConflict() {
        UUID cessionId = UUID.randomUUID();
        Cession auto = facultativeCession(cessionId, "ACTIVE");
        auto.setSource("AUTOMATIC");
        when(cessionRepository.findById(cessionId)).thenReturn(Mono.just(auto));

        StepVerifier.create(service.approve(cessionId, ACTOR_ID, ACTOR_EMAIL))
                .expectError(IllegalStateException.class)
                .verify();

        verify(cessionRepository, never()).save(any());
    }

    @Test
    void approve_notDraft_isConflict() {
        UUID cessionId = UUID.randomUUID();
        Cession approved = facultativeCession(cessionId, "APPROVED");
        when(cessionRepository.findById(cessionId)).thenReturn(Mono.just(approved));

        StepVerifier.create(service.approve(cessionId, ACTOR_ID, ACTOR_EMAIL))
                .expectError(IllegalStateException.class)
                .verify();

        verify(cessionRepository, never()).save(any());
    }

    @Test
    void approve_notFound_isBadRequest() {
        UUID cessionId = UUID.randomUUID();
        when(cessionRepository.findById(cessionId)).thenReturn(Mono.empty());

        StepVerifier.create(service.approve(cessionId, ACTOR_ID, ACTOR_EMAIL))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    // ── commit ─────────────────────────────────────────────────────────────

    @Test
    void commit_approvedFacultative_writesRecoveryAndAudits() {
        UUID cessionId = UUID.randomUUID();
        Cession approved = facultativeCession(cessionId, "APPROVED");
        when(cessionRepository.findById(cessionId)).thenReturn(Mono.just(approved));
        when(cessionRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(recoveryRepository.findByCessionId(cessionId)).thenReturn(Mono.empty());
        when(recoveryRepository.save(any())).thenAnswer(inv -> {
            Recovery r = inv.getArgument(0);
            r.setId(UUID.randomUUID());
            return Mono.just(r);
        });
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(service.commit(cessionId, ACTOR_ID, ACTOR_EMAIL))
                .assertNext(resp -> assertThat(resp.status()).isEqualTo("CEDED"))
                .verifyComplete();

        ArgumentCaptor<Recovery> rc = ArgumentCaptor.forClass(Recovery.class);
        verify(recoveryRepository).save(rc.capture());
        assertThat(rc.getValue().getStatus()).isEqualTo("EXPECTED");
        assertThat(rc.getValue().getExpectedAmount()).isEqualByComparingTo("5000");
        assertThat(rc.getValue().getCurrencyCode()).isEqualTo("USD");

        // audit: COMMIT on Cession + CREATE on Recovery
        ArgumentCaptor<AuditEvent> ac = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher, times(2)).publish(ac.capture());
        assertThat(ac.getAllValues())
                .extracting(AuditEvent::action)
                .containsExactly("COMMIT", "CREATE");
    }

    @Test
    void commit_recoveryAlreadyExists_skipsRecoveryButStillTransitions() {
        UUID cessionId = UUID.randomUUID();
        Cession approved = facultativeCession(cessionId, "APPROVED");
        Recovery existing = new Recovery();
        existing.setId(UUID.randomUUID());
        when(cessionRepository.findById(cessionId)).thenReturn(Mono.just(approved));
        when(cessionRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(recoveryRepository.findByCessionId(cessionId)).thenReturn(Mono.just(existing));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(service.commit(cessionId, ACTOR_ID, ACTOR_EMAIL))
                .assertNext(resp -> assertThat(resp.status()).isEqualTo("CEDED"))
                .verifyComplete();

        verify(recoveryRepository, never()).save(any());
        verify(auditPublisher, times(1)).publish(any());   // cession COMMIT only
    }

    @Test
    void commit_notApproved_isConflict() {
        UUID cessionId = UUID.randomUUID();
        Cession draft = facultativeCession(cessionId, "DRAFT");
        when(cessionRepository.findById(cessionId)).thenReturn(Mono.just(draft));

        StepVerifier.create(service.commit(cessionId, ACTOR_ID, ACTOR_EMAIL))
                .expectError(IllegalStateException.class)
                .verify();

        verify(cessionRepository, never()).save(any());
    }

    // ── voidCession ────────────────────────────────────────────────────────

    @Test
    void voidCession_draft_cascadeVoidsPendingRecovery() {
        UUID cessionId = UUID.randomUUID();
        Cession draft = facultativeCession(cessionId, "DRAFT");
        Recovery expected = new Recovery();
        expected.setId(UUID.randomUUID());
        expected.setCessionId(cessionId);
        expected.setStatus("EXPECTED");
        expected.setExpectedAmount(new BigDecimal("5000"));
        expected.setCurrencyCode("USD");

        when(cessionRepository.findById(cessionId)).thenReturn(Mono.just(draft));
        when(cessionRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(recoveryRepository.findByCessionId(cessionId)).thenReturn(Mono.just(expected));
        when(recoveryRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(service.voidCession(cessionId, "reinsurer refused",
                        ACTOR_ID, ACTOR_EMAIL))
                .assertNext(resp -> {
                    assertThat(resp.status()).isEqualTo("VOIDED");
                    assertThat(resp.voidedReason()).isEqualTo("reinsurer refused");
                })
                .verifyComplete();

        ArgumentCaptor<Recovery> rc = ArgumentCaptor.forClass(Recovery.class);
        verify(recoveryRepository).save(rc.capture());
        assertThat(rc.getValue().getStatus()).isEqualTo("WRITTEN_OFF");
        assertThat(rc.getValue().getWriteOffReason()).startsWith("Cession voided:");
    }

    @Test
    void voidCession_approved_cascadeVoidsInvoicedRecovery() {
        UUID cessionId = UUID.randomUUID();
        Cession approved = facultativeCession(cessionId, "APPROVED");
        Recovery invoiced = new Recovery();
        invoiced.setId(UUID.randomUUID());
        invoiced.setCessionId(cessionId);
        invoiced.setStatus("INVOICED");
        invoiced.setExpectedAmount(new BigDecimal("5000"));
        invoiced.setCurrencyCode("USD");

        when(cessionRepository.findById(cessionId)).thenReturn(Mono.just(approved));
        when(cessionRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(recoveryRepository.findByCessionId(cessionId)).thenReturn(Mono.just(invoiced));
        when(recoveryRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(service.voidCession(cessionId, "dispute", ACTOR_ID, ACTOR_EMAIL))
                .assertNext(resp -> assertThat(resp.status()).isEqualTo("VOIDED"))
                .verifyComplete();

        verify(recoveryRepository).save(any());
    }

    @Test
    void voidCession_terminalRecovery_isLeftAlone() {
        UUID cessionId = UUID.randomUUID();
        Cession draft = facultativeCession(cessionId, "DRAFT");
        Recovery received = new Recovery();
        received.setId(UUID.randomUUID());
        received.setCessionId(cessionId);
        received.setStatus("RECEIVED");
        received.setExpectedAmount(new BigDecimal("5000"));
        received.setCurrencyCode("USD");

        when(cessionRepository.findById(cessionId)).thenReturn(Mono.just(draft));
        when(cessionRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(recoveryRepository.findByCessionId(cessionId)).thenReturn(Mono.just(received));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(service.voidCession(cessionId, "cleanup", ACTOR_ID, ACTOR_EMAIL))
                .assertNext(resp -> assertThat(resp.status()).isEqualTo("VOIDED"))
                .verifyComplete();

        verify(recoveryRepository, never()).save(any());
    }

    @Test
    void voidCession_missingReason_isBadRequest() {
        StepVerifier.create(service.voidCession(UUID.randomUUID(), "  ", ACTOR_ID, ACTOR_EMAIL))
                .expectError(IllegalArgumentException.class)
                .verify();
        verify(cessionRepository, never()).findById(any(UUID.class));
    }

    @Test
    void voidCession_ceded_isConflict() {
        UUID cessionId = UUID.randomUUID();
        Cession ceded = facultativeCession(cessionId, "CEDED");
        when(cessionRepository.findById(cessionId)).thenReturn(Mono.just(ceded));

        StepVerifier.create(service.voidCession(cessionId, "reason", ACTOR_ID, ACTOR_EMAIL))
                .expectError(IllegalStateException.class)
                .verify();

        verify(cessionRepository, never()).save(any());
    }

    @Test
    void voidCession_nonFacultative_isConflict() {
        UUID cessionId = UUID.randomUUID();
        Cession auto = facultativeCession(cessionId, "ACTIVE");
        auto.setSource("AUTOMATIC");
        when(cessionRepository.findById(cessionId)).thenReturn(Mono.just(auto));

        StepVerifier.create(service.voidCession(cessionId, "reason", ACTOR_ID, ACTOR_EMAIL))
                .expectError(IllegalStateException.class)
                .verify();

        verify(cessionRepository, never()).save(any());
    }

    // ── fixtures ───────────────────────────────────────────────────────────

    private static CreateFacultativeCessionRequest request(UUID claimId, UUID treatyId,
                                                           String ceded, String basis) {
        return new CreateFacultativeCessionRequest(
                claimId, treatyId, null,
                new BigDecimal(ceded), new BigDecimal(basis),
                "USD", "captured off provider invoice");
    }

    private static Treaty activeTreaty(UUID id) {
        Treaty t = new Treaty();
        t.setId(id);
        t.setTreatyRef("T-" + id.toString().substring(0, 8));
        t.setTreatyType("QUOTA_SHARE");
        t.setDeclaredCurrency("USD");
        t.setInceptionDate(LocalDate.now().minusDays(30));
        t.setExpiryDate(LocalDate.now().plusDays(300));
        t.setStatus("ACTIVE");
        return t;
    }

    private static TreatyApplicableLine applicableLine(UUID treatyId, String line) {
        TreatyApplicableLine l = new TreatyApplicableLine();
        l.setTreatyId(treatyId);
        l.setInsuranceLine(line);
        return l;
    }

    private static Cession facultativeCession(UUID cessionId, String status) {
        Cession c = new Cession();
        c.setId(cessionId);
        c.setTreatyId(UUID.randomUUID());
        c.setCessionType("LOSS");
        c.setSource("FACULTATIVE");
        c.setStatus(status);
        c.setSourceEventId(UUID.randomUUID());
        c.setSourceEventType("CLAIM_FACULTATIVE");
        c.setCededAmount(new BigDecimal("5000"));
        c.setBasisAmount(new BigDecimal("10000"));
        c.setCurrencyCode("USD");
        c.setOccurredAt(OffsetDateTime.now());
        return c;
    }
}

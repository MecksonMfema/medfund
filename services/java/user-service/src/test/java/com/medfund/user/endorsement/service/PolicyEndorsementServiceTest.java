package com.medfund.user.endorsement.service;

import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.user.client.TenantEndorsementConfigClient;
import com.medfund.user.endorsement.dto.CreateEndorsementRequest;
import com.medfund.user.endorsement.entity.Endorsement;
import com.medfund.user.endorsement.repository.EndorsementRepository;
import com.medfund.user.endorsement.util.EndorsementReferenceGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PolicyEndorsementService} — pure Mockito, no
 * Postgres. Covers auto-commit vs four-eyes routing, every state
 * transition branch, and audit event shape.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PolicyEndorsementServiceTest {

    private static final String DRAFTER_ID     = "aaaaaaaa-0000-4000-8000-000000000001";
    private static final String DRAFTER_EMAIL  = "drafter@medfund";
    private static final String APPROVER_ID    = "bbbbbbbb-0000-4000-8000-000000000002";
    private static final String APPROVER_EMAIL = "approver@medfund";
    private static final String REASON         = "Cover uplift requested by the member's employer group";

    @Mock EndorsementRepository endorsementRepository;
    @Mock EndorsementReferenceGenerator referenceGenerator;
    @Mock TenantEndorsementConfigClient tenantConfigClient;
    @Mock PolicyEndorsedPublisher endorsedPublisher;
    @Mock R2dbcEntityTemplate r2dbcTemplate;
    @Mock AuditPublisher auditPublisher;

    PolicyEndorsementService service;

    @BeforeEach
    void setUp() {
        service = new PolicyEndorsementService(endorsementRepository, referenceGenerator,
                tenantConfigClient, endorsedPublisher, r2dbcTemplate, auditPublisher);
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        when(endorsedPublisher.publish(any(), any())).thenReturn(Mono.empty());
    }

    // ── createDraft (validation) ─────────────────────────────────────────

    @Test
    void createDraft_shortReason_isBadRequest() {
        CreateEndorsementRequest req = request().reason("nope").build();

        StepVerifier.create(service.createDraft(req, DRAFTER_ID, DRAFTER_EMAIL))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(IllegalArgumentException.class);
                    assertThat(err.getMessage()).contains("10 characters");
                })
                .verify();

        verify(r2dbcTemplate, never()).insert(any());
    }

    @Test
    void createDraft_unpairedPremiumCurrency_isBadRequest() {
        CreateEndorsementRequest req = request()
                .premiumDelta(new BigDecimal("100.00"))
                .currencyCode(null).build();

        StepVerifier.create(service.createDraft(req, DRAFTER_ID, DRAFTER_EMAIL))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(IllegalArgumentException.class);
                    assertThat(err.getMessage()).contains("provided together");
                })
                .verify();
    }

    @Test
    void createDraft_zeroPremiumDelta_isBadRequest() {
        CreateEndorsementRequest req = request()
                .premiumDelta(BigDecimal.ZERO).currencyCode("USD").build();

        StepVerifier.create(service.createDraft(req, DRAFTER_ID, DRAFTER_EMAIL))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(IllegalArgumentException.class);
                    assertThat(err.getMessage()).contains("non-zero");
                })
                .verify();
    }

    // ── createDraft (auto-commit vs four-eyes routing) ───────────────────

    @Test
    void createDraft_configDisabled_autoCommitsAndFiresEvent() {
        stubHappyInsert();
        when(tenantConfigClient.get(any())).thenReturn(
                Mono.just(TenantEndorsementConfigClient.Snapshot.disabled(null)));
        when(endorsementRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        CreateEndorsementRequest req = request()
                .premiumDelta(new BigDecimal("2000.00")).currencyCode("USD").build();

        StepVerifier.create(service.createDraft(req, DRAFTER_ID, DRAFTER_EMAIL))
                .assertNext(resp -> {
                    assertThat(resp.status()).isEqualTo("COMMITTED");
                    assertThat(resp.commitActorId().toString()).isEqualTo(DRAFTER_ID);
                    assertThat(resp.commitAt()).isNotNull();
                })
                .verifyComplete();

        verify(endorsedPublisher, times(1)).publish(any(), any());
    }

    @Test
    void createDraft_belowThreshold_autoCommitsAndFiresEvent() {
        stubHappyInsert();
        when(endorsementRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(tenantConfigClient.get(any())).thenReturn(Mono.just(
                new TenantEndorsementConfigClient.Snapshot(null, true, new BigDecimal("500.00"), "USD")));

        CreateEndorsementRequest req = request()
                .premiumDelta(new BigDecimal("100.00")).currencyCode("USD").build();

        StepVerifier.create(service.createDraft(req, DRAFTER_ID, DRAFTER_EMAIL))
                .assertNext(resp -> assertThat(resp.status()).isEqualTo("COMMITTED"))
                .verifyComplete();

        verify(endorsedPublisher, times(1)).publish(any(), any());
    }

    @Test
    void createDraft_atOrAboveThreshold_parksAtDraftNoEvent() {
        stubHappyInsert();
        when(tenantConfigClient.get(any())).thenReturn(Mono.just(
                new TenantEndorsementConfigClient.Snapshot(null, true, new BigDecimal("500.00"), "USD")));

        CreateEndorsementRequest req = request()
                .premiumDelta(new BigDecimal("500.00")).currencyCode("USD").build();

        StepVerifier.create(service.createDraft(req, DRAFTER_ID, DRAFTER_EMAIL))
                .assertNext(resp -> {
                    assertThat(resp.status()).isEqualTo("DRAFT");
                    assertThat(resp.commitAt()).isNull();
                })
                .verifyComplete();

        verify(endorsedPublisher, never()).publish(any(), any());
        // Only the CREATE audit fires — no auto-commit audit.
        verify(auditPublisher, times(1)).publish(any(AuditEvent.class));
    }

    @Test
    void createDraft_thresholdCurrencyMismatch_autoCommits() {
        // A USD-threshold does not gate a ZWL endorsement — auto-commit path.
        stubHappyInsert();
        when(endorsementRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(tenantConfigClient.get(any())).thenReturn(Mono.just(
                new TenantEndorsementConfigClient.Snapshot(null, true, new BigDecimal("500.00"), "USD")));

        CreateEndorsementRequest req = request()
                .premiumDelta(new BigDecimal("10000.00")).currencyCode("ZWL").build();

        StepVerifier.create(service.createDraft(req, DRAFTER_ID, DRAFTER_EMAIL))
                .assertNext(resp -> assertThat(resp.status()).isEqualTo("COMMITTED"))
                .verifyComplete();
    }

    @Test
    void createDraft_snapsEffectiveFromToFirstOfMonth() {
        stubHappyInsert();
        when(tenantConfigClient.get(any())).thenReturn(Mono.just(
                new TenantEndorsementConfigClient.Snapshot(null, true, new BigDecimal("100.00"), "USD")));

        CreateEndorsementRequest req = request()
                .effectiveFrom(LocalDate.of(2026, 4, 17))
                .premiumDelta(new BigDecimal("2000.00")).currencyCode("USD").build();

        StepVerifier.create(service.createDraft(req, DRAFTER_ID, DRAFTER_EMAIL))
                .assertNext(resp -> assertThat(resp.effectiveFrom()).isEqualTo(LocalDate.of(2026, 4, 1)))
                .verifyComplete();
    }

    @Test
    void createDraft_negativePremiumDelta_usesMagnitudeAgainstThreshold() {
        // -600 magnitude > 500 threshold → parks at DRAFT.
        stubHappyInsert();
        when(tenantConfigClient.get(any())).thenReturn(Mono.just(
                new TenantEndorsementConfigClient.Snapshot(null, true, new BigDecimal("500.00"), "USD")));

        CreateEndorsementRequest req = request()
                .premiumDelta(new BigDecimal("-600.00")).currencyCode("USD").build();

        StepVerifier.create(service.createDraft(req, DRAFTER_ID, DRAFTER_EMAIL))
                .assertNext(resp -> assertThat(resp.status()).isEqualTo("DRAFT"))
                .verifyComplete();

        verify(endorsedPublisher, never()).publish(any(), any());
    }

    // ── approve ──────────────────────────────────────────────────────────

    @Test
    void approve_happyPath_flipsToApprovedAndAudits() {
        UUID id = UUID.randomUUID();
        Endorsement existing = draftFixture(id);
        when(endorsementRepository.findById(id)).thenReturn(Mono.just(existing));
        when(endorsementRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service.approve(id, APPROVER_ID, APPROVER_EMAIL))
                .assertNext(resp -> {
                    assertThat(resp.status()).isEqualTo("APPROVED");
                    assertThat(resp.approveActorId().toString()).isEqualTo(APPROVER_ID);
                    assertThat(resp.approveAt()).isNotNull();
                })
                .verifyComplete();

        ArgumentCaptor<AuditEvent> cap = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher).publish(cap.capture());
        assertThat(cap.getValue().action()).isEqualTo("APPROVE");
        assertThat(cap.getValue().entityName()).isEqualTo("END-2026-000001");
    }

    @Test
    void approve_sameActor_isFourEyesViolation() {
        UUID id = UUID.randomUUID();
        Endorsement existing = draftFixture(id);
        when(endorsementRepository.findById(id)).thenReturn(Mono.just(existing));

        StepVerifier.create(service.approve(id, DRAFTER_ID, DRAFTER_EMAIL))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(IllegalStateException.class);
                    assertThat(err.getMessage()).contains("four-eyes");
                })
                .verify();

        verify(endorsementRepository, never()).save(any());
    }

    @Test
    void approve_notDraft_isConflict() {
        UUID id = UUID.randomUUID();
        Endorsement existing = draftFixture(id);
        existing.setStatus("APPROVED");
        when(endorsementRepository.findById(id)).thenReturn(Mono.just(existing));

        StepVerifier.create(service.approve(id, APPROVER_ID, APPROVER_EMAIL))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(IllegalStateException.class);
                    assertThat(err.getMessage()).contains("Only DRAFT");
                })
                .verify();
    }

    // ── commit ───────────────────────────────────────────────────────────

    @Test
    void commit_happyPath_flipsToCommittedAndFiresEvent() {
        UUID id = UUID.randomUUID();
        Endorsement existing = draftFixture(id);
        existing.setStatus("APPROVED");
        when(endorsementRepository.findById(id)).thenReturn(Mono.just(existing));
        when(endorsementRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service.commit(id, APPROVER_ID, APPROVER_EMAIL))
                .assertNext(resp -> {
                    assertThat(resp.status()).isEqualTo("COMMITTED");
                    assertThat(resp.commitActorId().toString()).isEqualTo(APPROVER_ID);
                    assertThat(resp.commitAt()).isNotNull();
                })
                .verifyComplete();

        verify(endorsedPublisher, times(1)).publish(any(), any());
    }

    @Test
    void commit_notApproved_isConflict() {
        UUID id = UUID.randomUUID();
        Endorsement existing = draftFixture(id);
        when(endorsementRepository.findById(id)).thenReturn(Mono.just(existing));

        StepVerifier.create(service.commit(id, APPROVER_ID, APPROVER_EMAIL))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(IllegalStateException.class);
                    assertThat(err.getMessage()).contains("Only APPROVED");
                })
                .verify();

        verify(endorsedPublisher, never()).publish(any(), any());
    }

    // ── void ─────────────────────────────────────────────────────────────

    @Test
    void voidEndorsement_fromDraft_happyPath() {
        UUID id = UUID.randomUUID();
        Endorsement existing = draftFixture(id);
        when(endorsementRepository.findById(id)).thenReturn(Mono.just(existing));
        when(endorsementRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service.voidEndorsement(id, "no longer needed", APPROVER_ID, APPROVER_EMAIL))
                .assertNext(resp -> {
                    assertThat(resp.status()).isEqualTo("VOIDED");
                    assertThat(resp.voidedReason()).isEqualTo("no longer needed");
                    assertThat(resp.voidedAt()).isNotNull();
                })
                .verifyComplete();
    }

    @Test
    void voidEndorsement_fromApproved_happyPath() {
        UUID id = UUID.randomUUID();
        Endorsement existing = draftFixture(id);
        existing.setStatus("APPROVED");
        when(endorsementRepository.findById(id)).thenReturn(Mono.just(existing));
        when(endorsementRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service.voidEndorsement(id, "wrong scheme", APPROVER_ID, APPROVER_EMAIL))
                .assertNext(resp -> assertThat(resp.status()).isEqualTo("VOIDED"))
                .verifyComplete();
    }

    @Test
    void voidEndorsement_fromCommitted_isRejected() {
        UUID id = UUID.randomUUID();
        Endorsement existing = draftFixture(id);
        existing.setStatus("COMMITTED");
        when(endorsementRepository.findById(id)).thenReturn(Mono.just(existing));

        StepVerifier.create(service.voidEndorsement(id, "too late", APPROVER_ID, APPROVER_EMAIL))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(IllegalStateException.class);
                    assertThat(err.getMessage()).contains("Cannot void a COMMITTED");
                })
                .verify();

        verify(endorsementRepository, never()).save(any());
    }

    @Test
    void voidEndorsement_missingReason_isRejected() {
        UUID id = UUID.randomUUID();
        StepVerifier.create(service.voidEndorsement(id, "  ", APPROVER_ID, APPROVER_EMAIL))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    // ── markComputed ─────────────────────────────────────────────────────

    @Test
    void markComputed_fromCommitted_transitionsAndAudits() {
        UUID id = UUID.randomUUID();
        Endorsement existing = draftFixture(id);
        existing.setStatus("COMMITTED");
        when(endorsementRepository.findById(id)).thenReturn(Mono.just(existing));
        when(endorsementRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service.markComputed(id, "system", "system@medfund"))
                .assertNext(resp -> assertThat(resp.status()).isEqualTo("COMPUTED"))
                .verifyComplete();

        ArgumentCaptor<AuditEvent> cap = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher).publish(cap.capture());
        assertThat(cap.getValue().action()).isEqualTo("COMPUTE");
    }

    // ── queue ────────────────────────────────────────────────────────────

    @Test
    void queue_defaultsToDraftPlusApproved() {
        when(endorsementRepository.findByStatusInOrderByCreatedAtAsc(any(), any()))
                .thenReturn(reactor.core.publisher.Flux.empty());

        StepVerifier.create(service.queue(null, 0, 50)).verifyComplete();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> statuses = ArgumentCaptor.forClass(List.class);
        verify(endorsementRepository).findByStatusInOrderByCreatedAtAsc(
                (java.util.Collection<String>) statuses.capture(), any());
        assertThat(statuses.getValue()).containsExactly("DRAFT", "APPROVED");
    }

    // ── fixtures ─────────────────────────────────────────────────────────

    private void stubHappyInsert() {
        when(referenceGenerator.nextEndorsementReference())
                .thenReturn(Mono.just("END-" + LocalDate.now().getYear() + "-000001"));
        when(r2dbcTemplate.insert(any(Endorsement.class))).thenAnswer(inv -> {
            Endorsement e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return Mono.just(e);
        });
    }

    private Endorsement draftFixture(UUID id) {
        Endorsement e = new Endorsement();
        e.setId(id);
        e.setReference("END-2026-000001");
        e.setPolicyId(UUID.randomUUID());
        e.setPolicySource("LIFE_POLICY");
        e.setInsuranceLine("LIFE");
        e.setChangeType("PREMIUM_ADJUSTMENT");
        e.setEffectiveFrom(LocalDate.of(2026, 4, 1));
        e.setPremiumDelta(new BigDecimal("100.00"));
        e.setCurrencyCode("USD");
        e.setReason(REASON);
        e.setStatus("DRAFT");
        e.setDraftActorId(UUID.fromString(DRAFTER_ID));
        e.setDraftActorEmail(DRAFTER_EMAIL);
        e.setDraftAt(Instant.now());
        e.setCreatedAt(Instant.now());
        e.setUpdatedAt(Instant.now());
        return e;
    }

    private static ReqBuilder request() {
        return new ReqBuilder();
    }

    /** Tiny builder for CreateEndorsementRequest so tests read cleanly. */
    private static final class ReqBuilder {
        UUID policyId = UUID.randomUUID();
        String policySource = "LIFE_POLICY";
        String insuranceLine = "LIFE";
        String changeType = "PREMIUM_ADJUSTMENT";
        LocalDate effectiveFrom = LocalDate.of(2026, 5, 1);
        BigDecimal premiumDelta;
        String currencyCode;
        String reason = REASON;

        ReqBuilder premiumDelta(BigDecimal v) { this.premiumDelta = v; return this; }
        ReqBuilder currencyCode(String v)     { this.currencyCode = v; return this; }
        ReqBuilder effectiveFrom(LocalDate v) { this.effectiveFrom = v; return this; }
        ReqBuilder reason(String v)           { this.reason = v; return this; }

        CreateEndorsementRequest build() {
            return new CreateEndorsementRequest(policyId, policySource, insuranceLine,
                    changeType, effectiveFrom, premiumDelta, currencyCode, reason);
        }
    }
}

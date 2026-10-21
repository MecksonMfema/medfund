package com.medfund.finance.regulatory.aml.service;

import com.medfund.finance.regulatory.aml.AmlStrFilingXlsxService.AmlStrFilingRenderResult;
import com.medfund.finance.regulatory.aml.dto.CloseAmlAlertRequest;
import com.medfund.finance.regulatory.aml.dto.FileAmlAlertRequest;
import com.medfund.finance.regulatory.aml.dto.RaiseAmlAlertRequest;
import com.medfund.finance.regulatory.aml.dto.ReviewAmlAlertRequest;
import com.medfund.finance.regulatory.aml.entity.SuspiciousTransactionAlert;
import com.medfund.finance.regulatory.aml.kafka.SuspiciousTransactionEventPublisher;
import com.medfund.finance.regulatory.aml.repository.SuspiciousTransactionAlertRepository;
import com.medfund.shared.report.regulatory.TemplateSource;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.report.SuspiciousTransactionEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AmlAlertService}. Pure Mockito — covers every state
 * transition + guard branch + the audit-event shape (friendly entityName,
 * actorEmail carried through).
 */
@ExtendWith(MockitoExtension.class)
class AmlAlertServiceTest {

    private static final String TENANT_ID = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee";
    private static final String RAISER_ID = "aaaaaaaa-0000-4000-8000-000000000001";
    private static final String RAISER_EMAIL = "raiser@medfund";
    private static final String REVIEWER_ID = "bbbbbbbb-0000-4000-8000-000000000002";
    private static final String REVIEWER_EMAIL = "reviewer@medfund";
    private static final String FILER_ID = "cccccccc-0000-4000-8000-000000000003";
    private static final String FILER_EMAIL = "filer@medfund";
    private static final String CLOSER_ID = "dddddddd-0000-4000-8000-000000000004";
    private static final String CLOSER_EMAIL = "closer@medfund";
    private static final String DESCRIPTION = "Large round-number premium via cash on new member, "
            + "no supporting employer, warrants further review by compliance.";

    @Mock SuspiciousTransactionAlertRepository repository;
    @Mock AuditPublisher auditPublisher;
    @Mock SuspiciousTransactionEventPublisher eventPublisher;
    @Mock AmlStrFilingService strFilingService;
    @InjectMocks AmlAlertService service;

    @org.junit.jupiter.api.BeforeEach
    void stubEventPublisher() {
        // Every workflow transition calls into the event publisher after the audit.
        // Default it to a no-op so per-test setup only overrides when it needs to.
        org.mockito.Mockito.lenient()
                .when(eventPublisher.publish(any())).thenReturn(Mono.empty());
        // Phase 26 file() auto-store — default to "no MinIO ref returned" so file()
        // falls back to the caller-supplied ref, preserving Phase 22 test expectations.
        org.mockito.Mockito.lenient()
                .when(strFilingService.renderAndStore(any(), any()))
                .thenReturn(Mono.just(new AmlStrFilingService.StoreResult(
                        new AmlStrFilingRenderResult("bytes".getBytes(),
                                TemplateSource.BUNDLED_SYNTHETIC, "SYNTHETIC_2024-01-01"),
                        java.util.Optional.empty())));
    }

    // ── raise ──────────────────────────────────────────────────────────────

    @Test
    void raise_happyPath_persistsRaisedStatusAndAudits() {
        when(repository.save(any())).thenAnswer(inv -> {
            SuspiciousTransactionAlert a = inv.getArgument(0);
            a.setId(UUID.randomUUID());
            return Mono.just(a);
        });
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(service.raise(raiseReq("TXN-2026-000001"), RAISER_ID, RAISER_EMAIL))
                .assertNext(resp -> {
                    assertThat(resp.status()).isEqualTo("RAISED");
                    assertThat(resp.transactionRef()).isEqualTo("TXN-2026-000001");
                    assertThat(resp.amountNative()).isEqualByComparingTo("15000.00");
                    assertThat(resp.currency()).isEqualTo("USD");
                    assertThat(resp.raisedByActorId().toString()).isEqualTo(RAISER_ID);
                    assertThat(resp.raisedByActorEmail()).isEqualTo(RAISER_EMAIL);
                    assertThat(resp.raisedAt()).isNotNull();
                })
                .verifyComplete();

        ArgumentCaptor<AuditEvent> cap = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher, times(1)).publish(cap.capture());
        AuditEvent event = cap.getValue();
        assertThat(event.entityType()).isEqualTo("SuspiciousTransactionAlert");
        assertThat(event.action()).isEqualTo("RAISE");
        assertThat(event.entityName()).isEqualTo("AML alert #TXN-2026-000001");
        assertThat(event.entityName()).doesNotStartWith(event.entityId());
        assertThat(event.actorEmail()).isEqualTo(RAISER_EMAIL);
    }

    // ── review ─────────────────────────────────────────────────────────────

    @Test
    void review_happyPath_flipsToReviewedAndAudits() {
        UUID id = UUID.randomUUID();
        SuspiciousTransactionAlert existing = raised(id);
        when(repository.findById(id)).thenReturn(Mono.just(existing));
        when(repository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(service.review(id,
                        new ReviewAmlAlertRequest("Triaged — matches Rand-round-number pattern"),
                        REVIEWER_ID, REVIEWER_EMAIL))
                .assertNext(resp -> {
                    assertThat(resp.status()).isEqualTo("REVIEWED");
                    assertThat(resp.reviewerActorId().toString()).isEqualTo(REVIEWER_ID);
                    assertThat(resp.reviewerActorEmail()).isEqualTo(REVIEWER_EMAIL);
                    assertThat(resp.reviewedAt()).isNotNull();
                    assertThat(resp.reviewNote()).startsWith("Triaged");
                })
                .verifyComplete();

        ArgumentCaptor<AuditEvent> cap = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher).publish(cap.capture());
        assertThat(cap.getValue().action()).isEqualTo("REVIEW");
        assertThat(cap.getValue().entityName()).isEqualTo("AML alert #TXN-000001");
    }

    @Test
    void review_notRaised_isConflict() {
        UUID id = UUID.randomUUID();
        SuspiciousTransactionAlert existing = raised(id);
        existing.setStatus("REVIEWED");
        when(repository.findById(id)).thenReturn(Mono.just(existing));

        StepVerifier.create(service.review(id,
                        new ReviewAmlAlertRequest("Cannot re-review a reviewed alert"),
                        REVIEWER_ID, REVIEWER_EMAIL))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(IllegalStateException.class);
                    assertThat(err.getMessage()).contains("Only RAISED");
                })
                .verify();

        verify(repository, never()).save(any());
    }

    // ── file ───────────────────────────────────────────────────────────────

    @Test
    void file_happyPath_flipsToFiledAndCapturesReference() {
        UUID id = UUID.randomUUID();
        SuspiciousTransactionAlert existing = raised(id);
        existing.setStatus("REVIEWED");
        when(repository.findById(id)).thenReturn(Mono.just(existing));
        when(repository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(service.file(id,
                        new FileAmlAlertRequest("FIU-STR-2026-0001234", "s3://aml/2026/xxx.xlsx"),
                        FILER_ID, FILER_EMAIL))
                .assertNext(resp -> {
                    assertThat(resp.status()).isEqualTo("FILED");
                    assertThat(resp.filedRef()).isEqualTo("FIU-STR-2026-0001234");
                    assertThat(resp.filedXlsxRef()).isEqualTo("s3://aml/2026/xxx.xlsx");
                    assertThat(resp.filerActorId().toString()).isEqualTo(FILER_ID);
                    assertThat(resp.filerActorEmail()).isEqualTo(FILER_EMAIL);
                    assertThat(resp.filedAt()).isNotNull();
                })
                .verifyComplete();

        ArgumentCaptor<AuditEvent> cap = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher).publish(cap.capture());
        assertThat(cap.getValue().action()).isEqualTo("FILE");
    }

    @Test
    void file_fromRaised_isConflict() {
        UUID id = UUID.randomUUID();
        SuspiciousTransactionAlert existing = raised(id);   // still RAISED, not REVIEWED
        when(repository.findById(id)).thenReturn(Mono.just(existing));

        StepVerifier.create(service.file(id,
                        new FileAmlAlertRequest("FIU-1", null),
                        FILER_ID, FILER_EMAIL))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(IllegalStateException.class);
                    assertThat(err.getMessage()).contains("Only REVIEWED");
                })
                .verify();

        verify(repository, never()).save(any());
    }

    @Test
    void file_autoStoreYieldsRef_thatRefIsPersistedOnTheAlert() {
        UUID id = UUID.randomUUID();
        SuspiciousTransactionAlert existing = raised(id);
        existing.setStatus("REVIEWED");
        when(repository.findById(id)).thenReturn(Mono.just(existing));
        when(repository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        when(strFilingService.renderAndStore(any(), any())).thenReturn(Mono.just(
                new AmlStrFilingService.StoreResult(
                        new AmlStrFilingRenderResult("xlsx".getBytes(),
                                TemplateSource.BUNDLED_SYNTHETIC, "SYNTHETIC_2024-01-01"),
                        java.util.Optional.of(
                                "s3://medfund-aml-filings/aml/str-filings/T/A/20260403-143000.xlsx"))));

        StepVerifier.create(service.file(id,
                        new FileAmlAlertRequest("FIU-STR-2026-0001234", null),
                        FILER_ID, FILER_EMAIL)
                        .contextWrite(ctx -> com.medfund.shared.tenant.TenantContext.put(ctx, TENANT_ID)))
                .assertNext(resp -> {
                    assertThat(resp.status()).isEqualTo("FILED");
                    assertThat(resp.filedXlsxRef())
                            .startsWith("s3://medfund-aml-filings/aml/str-filings/");
                })
                .verifyComplete();
    }

    @Test
    void file_autoStoreEmpty_fallsBackToCallerSuppliedRef() {
        UUID id = UUID.randomUUID();
        SuspiciousTransactionAlert existing = raised(id);
        existing.setStatus("REVIEWED");
        when(repository.findById(id)).thenReturn(Mono.just(existing));
        when(repository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        // Default lenient stub already returns Optional.empty() — caller-supplied ref is used.

        StepVerifier.create(service.file(id,
                        new FileAmlAlertRequest("FIU-STR-2026-0001234", "s3://pre-uploaded/manual.xlsx"),
                        FILER_ID, FILER_EMAIL)
                        .contextWrite(ctx -> com.medfund.shared.tenant.TenantContext.put(ctx, TENANT_ID)))
                .assertNext(resp -> assertThat(resp.filedXlsxRef())
                        .isEqualTo("s3://pre-uploaded/manual.xlsx"))
                .verifyComplete();
    }

    @Test
    void file_autoStoreThrows_isSwallowed_soFiledStillCommits() {
        UUID id = UUID.randomUUID();
        SuspiciousTransactionAlert existing = raised(id);
        existing.setStatus("REVIEWED");
        when(repository.findById(id)).thenReturn(Mono.just(existing));
        when(repository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        when(strFilingService.renderAndStore(any(), any()))
                .thenReturn(Mono.error(new RuntimeException("blob store down")));

        StepVerifier.create(service.file(id,
                        new FileAmlAlertRequest("FIU-STR-2026-0001234", null),
                        FILER_ID, FILER_EMAIL)
                        .contextWrite(ctx -> com.medfund.shared.tenant.TenantContext.put(ctx, TENANT_ID)))
                .assertNext(resp -> {
                    assertThat(resp.status()).isEqualTo("FILED");
                    // No ref because auto-store errored and caller supplied none.
                    assertThat(resp.filedXlsxRef()).isNull();
                })
                .verifyComplete();
    }

    @Test
    void file_withNoTenantContext_skipsAutoStore_gracefully() {
        UUID id = UUID.randomUUID();
        SuspiciousTransactionAlert existing = raised(id);
        existing.setStatus("REVIEWED");
        when(repository.findById(id)).thenReturn(Mono.just(existing));
        when(repository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        // No context — the auto-store branch short-circuits and the caller-supplied
        // ref (if any) still lands. strFilingService is never called.

        StepVerifier.create(service.file(id,
                        new FileAmlAlertRequest("FIU-STR-2026-0001234", "s3://manual/pre.xlsx"),
                        FILER_ID, FILER_EMAIL))
                .assertNext(resp -> {
                    assertThat(resp.status()).isEqualTo("FILED");
                    assertThat(resp.filedXlsxRef()).isEqualTo("s3://manual/pre.xlsx");
                })
                .verifyComplete();

        org.mockito.Mockito.verify(strFilingService, org.mockito.Mockito.never())
                .renderAndStore(any(), any());
    }

    // ── close ──────────────────────────────────────────────────────────────

    @Test
    void close_fromRaised_happyPath() {
        UUID id = UUID.randomUUID();
        SuspiciousTransactionAlert existing = raised(id);
        when(repository.findById(id)).thenReturn(Mono.just(existing));
        when(repository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(service.close(id,
                        new CloseAmlAlertRequest("Duplicate of #TXN-000000"),
                        CLOSER_ID, CLOSER_EMAIL))
                .assertNext(resp -> {
                    assertThat(resp.status()).isEqualTo("CLOSED");
                    assertThat(resp.closedReason()).isEqualTo("Duplicate of #TXN-000000");
                    assertThat(resp.closerActorId().toString()).isEqualTo(CLOSER_ID);
                    assertThat(resp.closerActorEmail()).isEqualTo(CLOSER_EMAIL);
                })
                .verifyComplete();

        ArgumentCaptor<AuditEvent> cap = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher).publish(cap.capture());
        assertThat(cap.getValue().action()).isEqualTo("CLOSE");
    }

    @Test
    void close_fromReviewed_happyPath() {
        UUID id = UUID.randomUUID();
        SuspiciousTransactionAlert existing = raised(id);
        existing.setStatus("REVIEWED");
        when(repository.findById(id)).thenReturn(Mono.just(existing));
        when(repository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(service.close(id,
                        new CloseAmlAlertRequest("Not reportable — legitimate premium prepayment"),
                        CLOSER_ID, CLOSER_EMAIL))
                .assertNext(resp -> assertThat(resp.status()).isEqualTo("CLOSED"))
                .verifyComplete();
    }

    @Test
    void close_fromFiled_isRejected() {
        UUID id = UUID.randomUUID();
        SuspiciousTransactionAlert existing = raised(id);
        existing.setStatus("FILED");
        when(repository.findById(id)).thenReturn(Mono.just(existing));

        StepVerifier.create(service.close(id,
                        new CloseAmlAlertRequest("Too late"),
                        CLOSER_ID, CLOSER_EMAIL))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(IllegalStateException.class);
                    assertThat(err.getMessage()).contains("Cannot close a FILED");
                })
                .verify();

        verify(repository, never()).save(any());
    }

    // ── queue ──────────────────────────────────────────────────────────────

    @Test
    void queue_defaultsToRaisedPlusReviewed() {
        when(repository.findByStatusInOrderByRaisedAtDesc(any(), any()))
                .thenReturn(reactor.core.publisher.Flux.empty());

        StepVerifier.create(service.queue(null, 0, 50))
                .verifyComplete();

        ArgumentCaptor<List<String>> statuses = ArgumentCaptor.forClass(List.class);
        verify(repository).findByStatusInOrderByRaisedAtDesc(statuses.capture(), any());
        assertThat(statuses.getValue()).containsExactly("RAISED", "REVIEWED");
    }

    // ── Kafka fan-out (Phase 24) ───────────────────────────────────────────

    @Test
    void raise_publishesKafkaEvent_withNullPriorStatusAndRaiseTransition() {
        when(repository.save(any())).thenAnswer(inv -> {
            SuspiciousTransactionAlert a = inv.getArgument(0);
            a.setId(UUID.randomUUID());
            return Mono.just(a);
        });
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        when(eventPublisher.publish(any())).thenReturn(Mono.empty());

        service.raise(raiseReq("TXN-KAFKA-001"), RAISER_ID, RAISER_EMAIL).block();

        ArgumentCaptor<SuspiciousTransactionEvent> cap =
                ArgumentCaptor.forClass(SuspiciousTransactionEvent.class);
        verify(eventPublisher).publish(cap.capture());
        SuspiciousTransactionEvent event = cap.getValue();
        assertThat(event.schemaVersion()).isEqualTo(SuspiciousTransactionEvent.CURRENT_SCHEMA_VERSION);
        assertThat(event.priorStatus()).isNull();
        assertThat(event.newStatus()).isEqualTo("RAISED");
        assertThat(event.transition()).isEqualTo(SuspiciousTransactionEvent.TRANSITION_RAISE);
        assertThat(event.transactionRef()).isEqualTo("TXN-KAFKA-001");
        assertThat(event.actorEmail()).isEqualTo(RAISER_EMAIL);
        assertThat(event.alertId()).isNotNull();
    }

    @Test
    void review_publishesKafkaEvent_withRaisedPriorStatusAndReviewTransition() {
        UUID id = UUID.randomUUID();
        SuspiciousTransactionAlert existing = raised(id);
        when(repository.findById(id)).thenReturn(Mono.just(existing));
        when(repository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        when(eventPublisher.publish(any())).thenReturn(Mono.empty());

        service.review(id,
                new ReviewAmlAlertRequest("Confirmed suspicious activity"),
                REVIEWER_ID, REVIEWER_EMAIL).block();

        ArgumentCaptor<SuspiciousTransactionEvent> cap =
                ArgumentCaptor.forClass(SuspiciousTransactionEvent.class);
        verify(eventPublisher).publish(cap.capture());
        SuspiciousTransactionEvent event = cap.getValue();
        assertThat(event.priorStatus()).isEqualTo("RAISED");
        assertThat(event.newStatus()).isEqualTo("REVIEWED");
        assertThat(event.transition()).isEqualTo(SuspiciousTransactionEvent.TRANSITION_REVIEW);
        assertThat(event.actorEmail()).isEqualTo(REVIEWER_EMAIL);
    }

    @Test
    void close_fromReviewed_publishesKafkaEvent_withReviewedPriorStatus() {
        UUID id = UUID.randomUUID();
        SuspiciousTransactionAlert existing = raised(id);
        existing.setStatus("REVIEWED");
        when(repository.findById(id)).thenReturn(Mono.just(existing));
        when(repository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        when(eventPublisher.publish(any())).thenReturn(Mono.empty());

        service.close(id,
                new CloseAmlAlertRequest("Not reportable"),
                CLOSER_ID, CLOSER_EMAIL).block();

        ArgumentCaptor<SuspiciousTransactionEvent> cap =
                ArgumentCaptor.forClass(SuspiciousTransactionEvent.class);
        verify(eventPublisher).publish(cap.capture());
        assertThat(cap.getValue().priorStatus()).isEqualTo("REVIEWED");
        assertThat(cap.getValue().newStatus()).isEqualTo("CLOSED");
        assertThat(cap.getValue().transition()).isEqualTo(SuspiciousTransactionEvent.TRANSITION_CLOSE);
    }

    @Test
    void publish_kafkaFailure_isSwallowedSoTransitionStillCommits() {
        // Simulate a broker outage — the transition itself must still succeed
        // because the workflow row is already committed + audit-logged. The
        // publisher error is swallowed and logged.
        when(repository.save(any())).thenAnswer(inv -> {
            SuspiciousTransactionAlert a = inv.getArgument(0);
            a.setId(UUID.randomUUID());
            return Mono.just(a);
        });
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        when(eventPublisher.publish(any()))
                .thenReturn(Mono.error(new RuntimeException("broker unavailable")));

        StepVerifier.create(service.raise(raiseReq("TXN-BROKER-DOWN"), RAISER_ID, RAISER_EMAIL))
                .assertNext(resp -> assertThat(resp.status()).isEqualTo("RAISED"))
                .verifyComplete();

        // Publisher was invoked once — its error was swallowed.
        verify(eventPublisher, times(1)).publish(any());
    }

    // ── audit shape ────────────────────────────────────────────────────────

    @Test
    void audit_entityName_isFriendlyTransactionRefNotUuid() {
        UUID id = UUID.randomUUID();
        SuspiciousTransactionAlert existing = raised(id);
        existing.setTransactionRef("TXN-2026-000042");
        when(repository.findById(id)).thenReturn(Mono.just(existing));
        when(repository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        service.review(id,
                        new ReviewAmlAlertRequest("Confirmed suspicious"),
                        REVIEWER_ID, REVIEWER_EMAIL)
                .block();

        ArgumentCaptor<AuditEvent> cap = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher).publish(cap.capture());
        assertThat(cap.getValue().entityName()).isEqualTo("AML alert #TXN-2026-000042");
        assertThat(cap.getValue().entityName()).isNotEqualTo(id.toString());
    }

    // ── fixtures ───────────────────────────────────────────────────────────

    private static RaiseAmlAlertRequest raiseReq(String ref) {
        return new RaiseAmlAlertRequest(ref, "PREMIUM",
                new BigDecimal("15000.00"), "USD",
                UUID.randomUUID(), null,
                DESCRIPTION);
    }

    private static SuspiciousTransactionAlert raised(UUID id) {
        SuspiciousTransactionAlert a = new SuspiciousTransactionAlert();
        a.setId(id);
        a.setStatus("RAISED");
        a.setTransactionRef("TXN-000001");
        a.setTransactionType("PREMIUM");
        a.setAmountNative(new BigDecimal("15000.00"));
        a.setCurrency("USD");
        a.setMemberId(UUID.randomUUID());
        a.setDescription(DESCRIPTION);
        a.setRaisedByActorId(UUID.fromString(RAISER_ID));
        a.setRaisedByActorEmail(RAISER_EMAIL);
        a.setRaisedAt(java.time.OffsetDateTime.now());
        return a;
    }
}

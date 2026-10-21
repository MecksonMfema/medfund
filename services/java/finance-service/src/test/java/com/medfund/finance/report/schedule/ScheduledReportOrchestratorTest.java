package com.medfund.finance.report.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.medfund.finance.report.entity.ReportJob;
import com.medfund.finance.report.repository.ReportJobRepository;
import com.medfund.finance.report.schedule.kafka.ReportDeliveryFailedPublisher;
import com.medfund.finance.report.schedule.kafka.ReportDeliveryPublisher;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.report.ReportDeliveryEvent;
import com.medfund.shared.report.ReportDeliveryFailedEvent;
import com.medfund.shared.report.ReportKey;
import com.medfund.shared.report.ReportPeriodShape;
import com.medfund.shared.security.SecurityEventPublisher;
import io.r2dbc.postgresql.codec.Json;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScheduledReportOrchestratorTest {

    @Mock private ReportJobRepository reportJobRepository;
    @Mock private ReportPayloadStore payloadStore;
    @Mock private ReportDeliveryPublisher deliveryPublisher;
    @Mock private ReportDeliveryFailedPublisher failedPublisher;
    @Mock private SecurityEventPublisher securityEventPublisher;
    @Mock private AuditPublisher auditPublisher;
    @Mock private ScheduledReportShapeAdapter commissionAdapter;

    private ScheduledReportOrchestrator orchestrator;

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID SCHEDULE_ID = UUID.randomUUID();
    private static final OffsetDateTime FIRED_AT = OffsetDateTime.parse("2026-09-01T08:05:00Z");

    @BeforeEach
    void setUp() {
        lenient().when(commissionAdapter.key()).thenReturn(ReportKey.COMMISSION_STATEMENT);
        lenient().when(commissionAdapter.periodShape()).thenReturn(ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD);

        Map<ReportKey, ScheduledReportShapeAdapter> adapters = new EnumMap<>(ReportKey.class);
        adapters.put(ReportKey.COMMISSION_STATEMENT, commissionAdapter);

        lenient().when(payloadStore.bucket()).thenReturn("medfund-report-payloads");
        lenient().when(payloadStore.putXlsx(anyString(), any(byte[].class))).thenReturn(Mono.empty());
        lenient().when(reportJobRepository.save(any(ReportJob.class)))
                .thenAnswer(inv -> Mono.just((ReportJob) inv.getArgument(0)));
        lenient().when(reportJobRepository.markCompleted(any(UUID.class), any(Json.class), any(OffsetDateTime.class)))
                .thenReturn(Mono.just(1));
        lenient().when(reportJobRepository.markFailed(any(UUID.class), anyString(), any(OffsetDateTime.class)))
                .thenReturn(Mono.just(1));
        lenient().when(reportJobRepository.findFirstByTenantIdAndReportKeyAndScheduleIdAndPeriodStart(
                any(UUID.class), anyString(), any(UUID.class), any(LocalDate.class)))
                .thenReturn(Mono.empty());
        lenient().when(deliveryPublisher.publish(any(ReportDeliveryEvent.class))).thenReturn(Mono.empty());
        lenient().when(failedPublisher.publish(any(ReportDeliveryFailedEvent.class))).thenReturn(Mono.empty());
        lenient().when(securityEventPublisher.publishDataAccess(anyString(), anyString(), anyString(),
                anyString(), any())).thenReturn(Mono.empty());
        lenient().when(auditPublisher.publish(any(AuditEvent.class))).thenReturn(Mono.empty());

        orchestrator = new ScheduledReportOrchestrator(
                adapters, reportJobRepository, Optional.of(payloadStore),
                deliveryPublisher, failedPublisher,
                securityEventPublisher, auditPublisher,
                new ObjectMapper().registerModule(new JavaTimeModule()),
                Clock.fixed(FIRED_AT.toInstant(), ZoneOffset.UTC));
    }

    private TenantScheduleFireCandidate candidate(String reportKey) {
        return new TenantScheduleFireCandidate(
                SCHEDULE_ID, TENANT_ID, reportKey, "MONTHLY",
                8, null, 1, "USD",
                UUID.randomUUID(), "admin@acme",
                "UTC", "acme", "Acme Corp", null);
    }

    @Test
    void happyPath_insertsRunsUploadsPublishesAndAudits() {
        byte[] bytes = new byte[]{1, 2, 3, 4};
        when(commissionAdapter.render(any(ScheduledFireContext.class))).thenReturn(Mono.just(bytes));

        StepVerifier.create(orchestrator.fireOnce(candidate("COMMISSION_STATEMENT"), FIRED_AT))
                .verifyComplete();

        verify(reportJobRepository).save(any(ReportJob.class));
        verify(payloadStore).putXlsx(anyString(), eq(bytes));
        verify(reportJobRepository).markCompleted(any(UUID.class), any(Json.class), any(OffsetDateTime.class));
        verify(reportJobRepository, never()).markFailed(any(UUID.class), anyString(), any(OffsetDateTime.class));

        ArgumentCaptor<ReportDeliveryEvent> evt = ArgumentCaptor.forClass(ReportDeliveryEvent.class);
        verify(deliveryPublisher).publish(evt.capture());
        assertThat(evt.getValue().reportKey()).isEqualTo("COMMISSION_STATEMENT");
        assertThat(evt.getValue().sizeBytes()).isEqualTo(bytes.length);
        assertThat(evt.getValue().tenantId()).isEqualTo(TENANT_ID);
        assertThat(evt.getValue().scheduleId()).isEqualTo(SCHEDULE_ID);

        verify(securityEventPublisher).publishDataAccess(
                eq(TENANT_ID.toString()), anyString(), anyString(),
                eq("COMMISSION_STATEMENT"), any());

        ArgumentCaptor<AuditEvent> audit = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher).publish(audit.capture());
        assertThat(audit.getValue().action()).isEqualTo("COMPLETED");
        assertThat(audit.getValue().entityName()).contains("Commission statement");
        assertThat(audit.getValue().entityName()).contains("acme");
    }

    @Test
    void adapterFailure_marksFailedAndPublishesFailedEvent() {
        when(commissionAdapter.render(any(ScheduledFireContext.class)))
                .thenReturn(Mono.error(new RuntimeException("shape service exploded")));

        StepVerifier.create(orchestrator.fireOnce(candidate("COMMISSION_STATEMENT"), FIRED_AT))
                .verifyComplete();

        verify(reportJobRepository).save(any(ReportJob.class));
        verify(reportJobRepository).markFailed(any(UUID.class),
                anyString(), any(OffsetDateTime.class));
        verify(reportJobRepository, never()).markCompleted(any(UUID.class), any(Json.class), any(OffsetDateTime.class));

        ArgumentCaptor<ReportDeliveryFailedEvent> evt = ArgumentCaptor.forClass(ReportDeliveryFailedEvent.class);
        verify(failedPublisher).publish(evt.capture());
        assertThat(evt.getValue().failureStage()).isEqualTo("SHAPE");
        assertThat(evt.getValue().errorSummary()).contains("shape service exploded");

        ArgumentCaptor<AuditEvent> audit = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher).publish(audit.capture());
        assertThat(audit.getValue().action()).isEqualTo("FAILED");
    }

    @Test
    void minioUploadFailure_isClassifiedAsMinioUploadStage() {
        when(commissionAdapter.render(any(ScheduledFireContext.class))).thenReturn(Mono.just(new byte[]{1}));
        when(payloadStore.putXlsx(anyString(), any(byte[].class)))
                .thenReturn(Mono.error(new ReportPayloadStore.ReportPayloadStoreException(
                        "putObject failed", new RuntimeException("net"))));

        StepVerifier.create(orchestrator.fireOnce(candidate("COMMISSION_STATEMENT"), FIRED_AT))
                .verifyComplete();

        ArgumentCaptor<ReportDeliveryFailedEvent> evt = ArgumentCaptor.forClass(ReportDeliveryFailedEvent.class);
        verify(failedPublisher).publish(evt.capture());
        assertThat(evt.getValue().failureStage()).isEqualTo("MINIO_UPLOAD");
    }

    @Test
    void unknownReportKey_isSkipped_noSideEffects() {
        StepVerifier.create(orchestrator.fireOnce(candidate("NOT_A_REAL_KEY"), FIRED_AT))
                .verifyComplete();
        verify(reportJobRepository, never()).save(any(ReportJob.class));
        verify(deliveryPublisher, never()).publish(any(ReportDeliveryEvent.class));
        verify(failedPublisher, never()).publish(any(ReportDeliveryFailedEvent.class));
    }

    @Test
    void noAdapter_isSkipped_noSideEffects() {
        // AGED_DEBTORS has no local adapter in this test's setup — should skip.
        StepVerifier.create(orchestrator.fireOnce(candidate("AGED_DEBTORS"), FIRED_AT))
                .verifyComplete();
        verify(reportJobRepository, never()).save(any(ReportJob.class));
    }

    @Test
    void duplicateKeyOnInsert_isSwallowed_asRaceLoss() {
        when(reportJobRepository.save(any(ReportJob.class)))
                .thenReturn(Mono.error(new DuplicateKeyException("ux_report_job_schedule_dedup")));

        StepVerifier.create(orchestrator.fireOnce(candidate("COMMISSION_STATEMENT"), FIRED_AT))
                .verifyComplete();

        verify(commissionAdapter, never()).render(any(ScheduledFireContext.class));
        verify(payloadStore, never()).putXlsx(anyString(), any(byte[].class));
        verify(deliveryPublisher, never()).publish(any(ReportDeliveryEvent.class));
    }

    @Test
    void preExistingRowDedupHit_skipsBeforeInsert() {
        ReportJob existing = new ReportJob();
        existing.setJobId(UUID.randomUUID());
        when(reportJobRepository.findFirstByTenantIdAndReportKeyAndScheduleIdAndPeriodStart(
                eq(TENANT_ID), eq("COMMISSION_STATEMENT"), eq(SCHEDULE_ID), any(LocalDate.class)))
                .thenReturn(Mono.just(existing));

        StepVerifier.create(orchestrator.fireOnce(candidate("COMMISSION_STATEMENT"), FIRED_AT))
                .verifyComplete();

        verify(reportJobRepository, never()).save(any(ReportJob.class));
        verify(commissionAdapter, never()).render(any(ScheduledFireContext.class));
    }

    @Test
    void classifyStage_recognisesMinioException() {
        assertThat(ScheduledReportOrchestrator.classifyStage(
                new ReportPayloadStore.ReportPayloadStoreException("x", new RuntimeException())))
                .isEqualTo("MINIO_UPLOAD");
    }

    @Test
    void classifyStage_defaultsToShape() {
        assertThat(ScheduledReportOrchestrator.classifyStage(new RuntimeException("anything")))
                .isEqualTo("SHAPE");
    }

    @Test
    void cadenceLabel_mapsAllKnownCadences() {
        assertThat(ScheduledReportOrchestrator.cadenceLabel("WEEKLY")).isEqualTo("Weekly");
        assertThat(ScheduledReportOrchestrator.cadenceLabel("MONTHLY")).isEqualTo("Monthly");
        assertThat(ScheduledReportOrchestrator.cadenceLabel("QUARTERLY")).isEqualTo("Quarterly");
        assertThat(ScheduledReportOrchestrator.cadenceLabel("ANNUAL")).isEqualTo("Annual");
        assertThat(ScheduledReportOrchestrator.cadenceLabel(null)).isEqualTo("Unknown");
    }

    @Test
    void sha256Hex_producesExpectedDigest() {
        // Known-good: SHA-256 of empty byte array = e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
        assertThat(ScheduledReportOrchestrator.sha256Hex(new byte[0]))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    void minioNotConfigured_isSkippedQuietly() {
        ScheduledReportOrchestrator noMinio = new ScheduledReportOrchestrator(
                Map.of(ReportKey.COMMISSION_STATEMENT, commissionAdapter),
                reportJobRepository, Optional.empty(),
                deliveryPublisher, failedPublisher,
                securityEventPublisher, auditPublisher,
                new ObjectMapper().registerModule(new JavaTimeModule()),
                Clock.fixed(FIRED_AT.toInstant(), ZoneOffset.UTC));
        StepVerifier.create(noMinio.fireOnce(candidate("COMMISSION_STATEMENT"), FIRED_AT))
                .verifyComplete();
        verify(reportJobRepository, never()).save(any(ReportJob.class));
    }

    @Test
    void render_isInvokedWithContextCarryingScheduleActor() {
        byte[] bytes = new byte[]{1};
        when(commissionAdapter.render(any(ScheduledFireContext.class))).thenReturn(Mono.just(bytes));

        TenantScheduleFireCandidate c = candidate("COMMISSION_STATEMENT");
        StepVerifier.create(orchestrator.fireOnce(c, FIRED_AT)).verifyComplete();

        ArgumentCaptor<ScheduledFireContext> ctx = ArgumentCaptor.forClass(ScheduledFireContext.class);
        verify(commissionAdapter, times(1)).render(ctx.capture());
        assertThat(ctx.getValue().tenantId()).isEqualTo(TENANT_ID);
        assertThat(ctx.getValue().scheduleUpdatedByActorId()).isEqualTo(c.scheduleUpdatedByActorId());
        assertThat(ctx.getValue().scheduleUpdatedByActorEmail()).isEqualTo("admin@acme");
        assertThat(ctx.getValue().reportingCurrency()).isEqualTo("USD");
    }

    @Test
    void truncate_capsLongMessages() {
        String tenChars = "0123456789";
        assertThat(ScheduledReportOrchestrator.truncate(tenChars, 5)).hasSize(5);
        assertThat(ScheduledReportOrchestrator.truncate(tenChars, 100)).isEqualTo(tenChars);
    }

    @Test
    void truncate_handlesNull() {
        assertThat(ScheduledReportOrchestrator.truncate(null, 5)).isEmpty();
    }

    @Test
    @SuppressWarnings("unused")
    void deliveryEventOccurredAt_isProvidedByClock() {
        byte[] bytes = new byte[]{1};
        when(commissionAdapter.render(any(ScheduledFireContext.class))).thenReturn(Mono.just(bytes));

        StepVerifier.create(orchestrator.fireOnce(candidate("COMMISSION_STATEMENT"), FIRED_AT))
                .verifyComplete();

        ArgumentCaptor<ReportDeliveryEvent> evt = ArgumentCaptor.forClass(ReportDeliveryEvent.class);
        verify(deliveryPublisher).publish(evt.capture());
        assertThat(evt.getValue().occurredAt()).isEqualTo(FIRED_AT.toInstant());
    }

    @Test
    void adapterRenderFailureAfterInsert_stillMarksFailed() {
        when(commissionAdapter.render(any(ScheduledFireContext.class)))
                .thenReturn(Mono.error(new IllegalStateException("boom")));

        StepVerifier.create(orchestrator.fireOnce(candidate("COMMISSION_STATEMENT"), FIRED_AT))
                .verifyComplete();

        verify(reportJobRepository).save(any(ReportJob.class));
        verify(reportJobRepository).markFailed(any(UUID.class), anyString(), any(OffsetDateTime.class));
    }

    // Deliberately unused import filter — keeps anyLong reference reachable for future assertions.
    @SuppressWarnings("unused")
    private static void _unusedMatcherReferenceKeeper() {
        anyLong();
    }
}

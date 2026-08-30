package com.medfund.user.service;

import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.user.entity.CohortStatusHistory;
import com.medfund.user.entity.Ifrs17Cohort;
import com.medfund.user.exception.Ifrs17CohortNotFoundException;
import com.medfund.user.repository.CohortStatusHistoryRepository;
import com.medfund.user.repository.Ifrs17CohortRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CohortStatusHistoryServiceTest {

    @Mock private CohortStatusHistoryRepository repository;
    @Mock private Ifrs17CohortRepository cohortRepository;
    @Mock private R2dbcEntityTemplate r2dbcTemplate;
    @Mock private DatabaseClient databaseClient;
    @Mock private AuditPublisher auditPublisher;
    @Mock private Ifrs17MaterialEventPublisher materialEventPublisher;
    @Mock private CohortLossComponentService lossComponentService;

    private CohortStatusHistoryService service;
    private final UUID cohortId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new CohortStatusHistoryService(repository, cohortRepository, r2dbcTemplate,
                databaseClient, auditPublisher, materialEventPublisher, lossComponentService);
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        when(materialEventPublisher.publish(any(), any(), any(), any(), any()))
                .thenReturn(Mono.empty());
    }

    @Test
    void findByCohortId_missingCohort_errorsNotFound() {
        when(cohortRepository.findById(cohortId)).thenReturn(Mono.empty());

        StepVerifier.create(service.findByCohortId(cohortId))
                .expectError(Ifrs17CohortNotFoundException.class)
                .verify();
    }

    @Test
    void recordTransition_manual_insertsAndPublishesAuditButNoMaterialEvent() {
        var cohort = cohort();
        when(cohortRepository.findById(cohortId)).thenReturn(Mono.just(cohort));
        when(r2dbcTemplate.insert(any(CohortStatusHistory.class))).thenAnswer(inv -> {
            CohortStatusHistory arg = inv.getArgument(0);
            arg.setId(UUID.randomUUID());
            arg.setCreatedAt(Instant.now());
            return Mono.just(arg);
        });

        UUID actorId = UUID.randomUUID();

        StepVerifier.create(service.recordTransition(cohortId, "NON_ONEROUS", "ONEROUS",
                        "MANUAL_OVERRIDE", "MANUAL", null, actorId, "alice@example.com",
                        "budget review"))
                .assertNext(row -> {
                    assertThat(row.getCohortId()).isEqualTo(cohortId);
                    assertThat(row.getFromStatus()).isEqualTo("NON_ONEROUS");
                    assertThat(row.getToStatus()).isEqualTo("ONEROUS");
                    assertThat(row.getTransitionSource()).isEqualTo("MANUAL");
                    assertThat(row.getActorEmail()).isEqualTo("alice@example.com");
                    assertThat(row.getReasonNote()).isEqualTo("budget review");
                })
                .verifyComplete();

        // Audit event fires exactly once with entityName = "from → to" (friendly text, not UUID).
        ArgumentCaptor<AuditEvent> auditCaptor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher, times(1)).publish(auditCaptor.capture());
        AuditEvent event = auditCaptor.getValue();
        assertThat(event.entityType()).isEqualTo("CohortStatusHistory");
        assertThat(event.entityName()).isEqualTo("NON_ONEROUS → ONEROUS");
        assertThat(event.action()).isEqualTo("CREATE");
        assertThat(event.actorEmail()).isEqualTo("alice@example.com");

        // MANUAL transitions do NOT emit a material event — that path is only for AUTO onerous
        // transitions (populated by Phase 15 §15 compute).
        verify(materialEventPublisher, never()).publish(any(), any(), any(), any(), any());
    }

    @Test
    void recordTransition_autoOnerous_alsoEmitsMaterialEvent() {
        var cohort = cohort();
        when(cohortRepository.findById(cohortId)).thenReturn(Mono.just(cohort));
        when(r2dbcTemplate.insert(any(CohortStatusHistory.class))).thenAnswer(inv -> {
            CohortStatusHistory arg = inv.getArgument(0);
            arg.setId(UUID.randomUUID());
            return Mono.just(arg);
        });

        UUID sourceRunId = UUID.randomUUID();

        StepVerifier.create(service.recordTransition(cohortId, "NON_ONEROUS", "ONEROUS",
                        "AUTO_TEST_FAILED", "AUTO", sourceRunId, null, "system@medfund",
                        "FCF exceeded remaining CSM"))
                .expectNextCount(1)
                .verifyComplete();

        verify(auditPublisher, times(1)).publish(any());
        // AUTO + to_status=ONEROUS → WARN severity per plan §4 / §15.
        verify(materialEventPublisher, times(1)).publish(
                eq(cohortId), eq("ONEROUS_TRANSITION"), eq("WARN"),
                any(), eq(sourceRunId));
    }

    @Test
    void recordTransition_autoRecovered_emitsInfoSeverity() {
        var cohort = cohort();
        when(cohortRepository.findById(cohortId)).thenReturn(Mono.just(cohort));
        when(r2dbcTemplate.insert(any(CohortStatusHistory.class))).thenAnswer(inv -> {
            CohortStatusHistory arg = inv.getArgument(0);
            arg.setId(UUID.randomUUID());
            return Mono.just(arg);
        });

        StepVerifier.create(service.recordTransition(cohortId, "ONEROUS", "NON_ONEROUS",
                        "AUTO_TEST_RECOVERED", "AUTO", UUID.randomUUID(), null,
                        "system@medfund", null))
                .expectNextCount(1)
                .verifyComplete();

        verify(materialEventPublisher, times(1)).publish(
                eq(cohortId), eq("ONEROUS_TRANSITION"), eq("INFO"),
                any(), any());
    }

    @Test
    void recordTransition_missingCohort_errorsBeforeAnyWrite() {
        when(cohortRepository.findById(cohortId)).thenReturn(Mono.empty());

        StepVerifier.create(service.recordTransition(cohortId, "NON_ONEROUS", "ONEROUS",
                        "MANUAL_OVERRIDE", "MANUAL", null, UUID.randomUUID(),
                        "alice@example.com", null))
                .expectError(Ifrs17CohortNotFoundException.class)
                .verify();

        verify(r2dbcTemplate, never()).insert(any(CohortStatusHistory.class));
        verify(auditPublisher, never()).publish(any());
        verify(materialEventPublisher, never()).publish(any(), any(), any(), any(), any());
    }

    private Ifrs17Cohort cohort() {
        var c = new Ifrs17Cohort();
        c.setId(cohortId);
        c.setName("HEALTH-2026-NON_ONEROUS");
        c.setCohortType("NON_ONEROUS");
        c.setCohortYear(2026);
        c.setIsActive(true);
        return c;
    }
}

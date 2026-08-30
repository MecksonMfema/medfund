package com.medfund.user.service;

import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.user.entity.CohortLossComponentHistory;
import com.medfund.user.entity.Ifrs17Cohort;
import com.medfund.user.exception.Ifrs17CohortNotFoundException;
import com.medfund.user.repository.CohortLossComponentHistoryRepository;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CohortLossComponentServiceTest {

    @Mock private CohortLossComponentHistoryRepository repository;
    @Mock private Ifrs17CohortRepository cohortRepository;
    @Mock private R2dbcEntityTemplate r2dbcTemplate;
    @Mock private DatabaseClient databaseClient;
    @Mock private AuditPublisher auditPublisher;

    private CohortLossComponentService service;
    private final UUID cohortId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new CohortLossComponentService(repository, cohortRepository, r2dbcTemplate,
                databaseClient, auditPublisher);
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
    }

    @Test
    void recordMovement_initialRecognition_insertsAndPublishesAuditWithFriendlyName() {
        when(cohortRepository.findById(cohortId)).thenReturn(Mono.just(cohort()));
        when(r2dbcTemplate.insert(any(CohortLossComponentHistory.class))).thenAnswer(inv -> {
            CohortLossComponentHistory arg = inv.getArgument(0);
            arg.setId(UUID.randomUUID());
            arg.setCreatedAt(Instant.now());
            return Mono.just(arg);
        });

        UUID actorId = UUID.randomUUID();
        StepVerifier.create(service.recordMovement(cohortId, "INITIAL_RECOGNITION",
                        new BigDecimal("50000.00"), "USD", null, actorId,
                        "alice@example.com", "budget review"))
                .assertNext(row -> {
                    assertThat(row.getCohortId()).isEqualTo(cohortId);
                    assertThat(row.getMovementType()).isEqualTo("INITIAL_RECOGNITION");
                    assertThat(row.getAmount()).isEqualByComparingTo("50000.00");
                    assertThat(row.getCurrency()).isEqualTo("USD");
                    assertThat(row.getActorEmail()).isEqualTo("alice@example.com");
                })
                .verifyComplete();

        // entityName is friendly text per feedback_audit_entity_name — never the UUID.
        ArgumentCaptor<AuditEvent> auditCaptor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher, times(1)).publish(auditCaptor.capture());
        AuditEvent event = auditCaptor.getValue();
        assertThat(event.entityType()).isEqualTo("CohortLossComponentHistory");
        assertThat(event.entityName()).isEqualTo("INITIAL_RECOGNITION 50000.00 USD");
        assertThat(event.action()).isEqualTo("CREATE");
        assertThat(event.actorEmail()).isEqualTo("alice@example.com");
    }

    @Test
    void recordMovement_release_alsoInsertsAndAudits() {
        when(cohortRepository.findById(cohortId)).thenReturn(Mono.just(cohort()));
        when(r2dbcTemplate.insert(any(CohortLossComponentHistory.class))).thenAnswer(inv -> {
            CohortLossComponentHistory arg = inv.getArgument(0);
            arg.setId(UUID.randomUUID());
            return Mono.just(arg);
        });

        StepVerifier.create(service.recordMovement(cohortId, "RELEASE",
                        new BigDecimal("1500.50"), "USD", UUID.randomUUID(), null,
                        "system@medfund", "period roll-forward"))
                .expectNextCount(1)
                .verifyComplete();

        verify(auditPublisher, times(1)).publish(any());
    }

    @Test
    void recordMovement_zeroOrNegativeAmount_errors() {
        // No cohortRepository call expected — the amount check happens first.
        StepVerifier.create(service.recordMovement(cohortId, "INITIAL_RECOGNITION",
                        BigDecimal.ZERO, "USD", null, UUID.randomUUID(),
                        "alice@example.com", null))
                .expectError(IllegalArgumentException.class)
                .verify();

        StepVerifier.create(service.recordMovement(cohortId, "INITIAL_RECOGNITION",
                        new BigDecimal("-10.00"), "USD", null, UUID.randomUUID(),
                        "alice@example.com", null))
                .expectError(IllegalArgumentException.class)
                .verify();

        verify(cohortRepository, never()).findById(any(UUID.class));
        verify(r2dbcTemplate, never()).insert(any(CohortLossComponentHistory.class));
    }

    @Test
    void recordMovement_unknownMovementType_errors() {
        StepVerifier.create(service.recordMovement(cohortId, "NOT_A_MOVEMENT",
                        new BigDecimal("100.00"), "USD", null, UUID.randomUUID(),
                        "alice@example.com", null))
                .expectError(IllegalArgumentException.class)
                .verify();

        verify(cohortRepository, never()).findById(any(UUID.class));
    }

    @Test
    void recordMovement_missingCohort_errorsBeforeAnyWrite() {
        when(cohortRepository.findById(cohortId)).thenReturn(Mono.empty());

        StepVerifier.create(service.recordMovement(cohortId, "INITIAL_RECOGNITION",
                        new BigDecimal("100.00"), "USD", null, UUID.randomUUID(),
                        "alice@example.com", null))
                .expectError(Ifrs17CohortNotFoundException.class)
                .verify();

        verify(r2dbcTemplate, never()).insert(any(CohortLossComponentHistory.class));
        verify(auditPublisher, never()).publish(any());
    }

    @Test
    void findByCohortId_missingCohort_errorsNotFound() {
        when(cohortRepository.findById(cohortId)).thenReturn(Mono.empty());

        StepVerifier.create(service.findByCohortId(cohortId))
                .expectError(Ifrs17CohortNotFoundException.class)
                .verify();
    }

    private Ifrs17Cohort cohort() {
        var c = new Ifrs17Cohort();
        c.setId(cohortId);
        c.setName("HEALTH-2026-ONEROUS");
        c.setCohortType("ONEROUS");
        c.setCohortYear(2026);
        c.setIsActive(true);
        return c;
    }
}

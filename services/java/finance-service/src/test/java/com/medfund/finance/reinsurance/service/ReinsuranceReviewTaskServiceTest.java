package com.medfund.finance.reinsurance.service;

import com.medfund.finance.reinsurance.entity.Cession;
import com.medfund.finance.reinsurance.entity.Recovery;
import com.medfund.finance.reinsurance.entity.ReinsuranceReviewTask;
import com.medfund.finance.reinsurance.repository.CessionRepository;
import com.medfund.finance.reinsurance.repository.RecoveryRepository;
import com.medfund.finance.reinsurance.repository.ReinsuranceReviewTaskRepository;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Focused unit tests on {@link ReinsuranceReviewTaskService}. Cover:
 * <ul>
 *   <li>{@code createRegressionTasks} — filters to prior cessions whose
 *       basis exceeds the new basis; skips already-open duplicates.</li>
 *   <li>{@code assign} — flips OPEN → IN_PROGRESS and records assignee;
 *       rejects assignment on resolved tasks.</li>
 *   <li>{@code resolve} — three resolutions; RESOLVED_VOID cascades to
 *       cession + non-terminal recovery.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ReinsuranceReviewTaskServiceTest {

    @Mock ReinsuranceReviewTaskRepository repository;
    @Mock CessionRepository cessionRepository;
    @Mock RecoveryRepository recoveryRepository;
    @Mock AuditPublisher auditPublisher;
    @InjectMocks ReinsuranceReviewTaskService service;

    @Test
    void createRegressionTasks_lowerBasis_opensOneTaskPerAffectedCession() {
        UUID claimId = UUID.randomUUID();
        Cession c1 = cession(UUID.randomUUID(), "1000.00", "300.00");
        Cession c2 = cession(UUID.randomUUID(), "1000.00", "500.00");
        when(repository.findOpenByCessionAndType(any(), eq("CLAIM_REGRESSION")))
                .thenReturn(Mono.empty());
        when(repository.save(any())).thenAnswer(inv -> {
            ReinsuranceReviewTask t = inv.getArgument(0);
            t.setId(UUID.randomUUID());
            return Mono.just(t);
        });
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
                service.createRegressionTasks(claimId, List.of(c1, c2),
                                new BigDecimal("500.00"), "sys", "sys@test")
                        .collectList()
        )
                .assertNext(tasks -> assertThat(tasks).hasSize(2))
                .verifyComplete();

        ArgumentCaptor<ReinsuranceReviewTask> cap = ArgumentCaptor.forClass(ReinsuranceReviewTask.class);
        verify(repository, org.mockito.Mockito.times(2)).save(cap.capture());
        assertThat(cap.getAllValues()).allSatisfy(t -> {
            assertThat(t.getTaskType()).isEqualTo("CLAIM_REGRESSION");
            assertThat(t.getClaimId()).isEqualTo(claimId);
            assertThat(t.getStatus()).isEqualTo("OPEN");
            assertThat(t.getCreateReason()).contains("re-adjudicated");
        });
    }

    @Test
    void createRegressionTasks_equalOrHigherBasis_writesNothing() {
        UUID claimId = UUID.randomUUID();
        Cession c1 = cession(UUID.randomUUID(), "500.00", "150.00");

        StepVerifier.create(
                service.createRegressionTasks(claimId, List.of(c1),
                                new BigDecimal("500.00"), "sys", "sys@test")
                        .collectList()
        )
                .assertNext(tasks -> assertThat(tasks).isEmpty())
                .verifyComplete();

        verify(repository, never()).save(any());
    }

    @Test
    void createRegressionTasks_alreadyOpenOnThisCession_skipsDuplicate() {
        UUID claimId = UUID.randomUUID();
        Cession prior = cession(UUID.randomUUID(), "1000.00", "300.00");
        ReinsuranceReviewTask existing = new ReinsuranceReviewTask();
        existing.setId(UUID.randomUUID());
        when(repository.findOpenByCessionAndType(prior.getId(), "CLAIM_REGRESSION"))
                .thenReturn(Mono.just(existing));

        StepVerifier.create(
                service.createRegressionTasks(claimId, List.of(prior),
                                new BigDecimal("500.00"), "sys", "sys@test")
                        .collectList()
        )
                .assertNext(tasks -> assertThat(tasks).isEmpty())
                .verifyComplete();

        verify(repository, never()).save(any());
    }

    @Test
    void assign_openTask_movesToInProgressAndEmitsAudit() {
        UUID taskId = UUID.randomUUID();
        UUID assignee = UUID.randomUUID();
        ReinsuranceReviewTask task = new ReinsuranceReviewTask();
        task.setId(taskId);
        task.setStatus("OPEN");
        task.setTaskType("CLAIM_REGRESSION");
        when(repository.findById(taskId)).thenReturn(Mono.just(task));
        when(repository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(service.assign(taskId, assignee, "sys", "sys@test"))
                .assertNext(resp -> {
                    assertThat(resp.status()).isEqualTo("IN_PROGRESS");
                    assertThat(resp.assigneeUserId()).isEqualTo(assignee);
                })
                .verifyComplete();

        ArgumentCaptor<AuditEvent> cap = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher).publish(cap.capture());
        assertThat(cap.getValue().action()).isEqualTo("ASSIGN");
    }

    @Test
    void assign_resolvedTask_errors() {
        UUID taskId = UUID.randomUUID();
        ReinsuranceReviewTask task = new ReinsuranceReviewTask();
        task.setId(taskId);
        task.setStatus("RESOLVED_KEEP");
        when(repository.findById(taskId)).thenReturn(Mono.just(task));

        StepVerifier.create(service.assign(taskId, UUID.randomUUID(), "sys", "sys@test"))
                .expectError(IllegalStateException.class)
                .verify();
    }

    @Test
    void resolve_keepDoesNotTouchCession() {
        UUID taskId = UUID.randomUUID();
        UUID cessionId = UUID.randomUUID();
        ReinsuranceReviewTask task = new ReinsuranceReviewTask();
        task.setId(taskId);
        task.setStatus("IN_PROGRESS");
        task.setTaskType("CLAIM_REGRESSION");
        task.setCessionId(cessionId);
        when(repository.findById(taskId)).thenReturn(Mono.just(task));
        when(repository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
                service.resolve(taskId, "RESOLVED_KEEP", "left cession in place", "sys", "sys@test")
        )
                .assertNext(resp -> assertThat(resp.status()).isEqualTo("RESOLVED_KEEP"))
                .verifyComplete();

        verify(cessionRepository, never()).findById(any(UUID.class));
        verify(cessionRepository, never()).save(any());
    }

    @Test
    void resolve_voidCascadesToCessionAndRecovery() {
        UUID taskId = UUID.randomUUID();
        UUID cessionId = UUID.randomUUID();
        ReinsuranceReviewTask task = new ReinsuranceReviewTask();
        task.setId(taskId);
        task.setStatus("IN_PROGRESS");
        task.setTaskType("CLAIM_REGRESSION");
        task.setCessionId(cessionId);
        Cession cession = cession(cessionId, "1000.00", "300.00");
        cession.setStatus("ACTIVE");
        Recovery recovery = new Recovery();
        recovery.setId(UUID.randomUUID());
        recovery.setCessionId(cessionId);
        recovery.setStatus("EXPECTED");
        recovery.setExpectedAmount(new BigDecimal("300.00"));
        recovery.setCurrencyCode("USD");
        when(repository.findById(taskId)).thenReturn(Mono.just(task));
        when(repository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(cessionRepository.findById(cessionId)).thenReturn(Mono.just(cession));
        when(cessionRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(recoveryRepository.findByCessionId(cessionId)).thenReturn(Mono.just(recovery));
        when(recoveryRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
                service.resolve(taskId, "RESOLVED_VOID", "wrong loss classification",
                                "sys", "sys@test")
        )
                .assertNext(resp -> assertThat(resp.status()).isEqualTo("RESOLVED_VOID"))
                .verifyComplete();

        ArgumentCaptor<Cession> cessionCap = ArgumentCaptor.forClass(Cession.class);
        verify(cessionRepository).save(cessionCap.capture());
        assertThat(cessionCap.getValue().getStatus()).isEqualTo("VOIDED");
        assertThat(cessionCap.getValue().getVoidedReason()).contains("wrong loss classification");

        ArgumentCaptor<Recovery> recoveryCap = ArgumentCaptor.forClass(Recovery.class);
        verify(recoveryRepository).save(recoveryCap.capture());
        assertThat(recoveryCap.getValue().getStatus()).isEqualTo("WRITTEN_OFF");
        assertThat(recoveryCap.getValue().getWriteOffReason()).contains("wrong loss classification");
    }

    @Test
    void resolve_voidWithTerminalRecovery_leavesRecoveryAlone() {
        UUID taskId = UUID.randomUUID();
        UUID cessionId = UUID.randomUUID();
        ReinsuranceReviewTask task = new ReinsuranceReviewTask();
        task.setId(taskId);
        task.setStatus("OPEN");
        task.setCessionId(cessionId);
        Cession cession = cession(cessionId, "1000.00", "300.00");
        cession.setStatus("ACTIVE");
        Recovery recovery = new Recovery();
        recovery.setId(UUID.randomUUID());
        recovery.setCessionId(cessionId);
        recovery.setStatus("RECEIVED");
        when(repository.findById(taskId)).thenReturn(Mono.just(task));
        when(repository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(cessionRepository.findById(cessionId)).thenReturn(Mono.just(cession));
        when(cessionRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(recoveryRepository.findByCessionId(cessionId)).thenReturn(Mono.just(recovery));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
                service.resolve(taskId, "RESOLVED_VOID", "reason", "sys", "sys@test")
        )
                .assertNext(resp -> assertThat(resp.status()).isEqualTo("RESOLVED_VOID"))
                .verifyComplete();

        verify(recoveryRepository, never()).save(any());
    }

    @Test
    void resolve_dismissClosesTaskWithoutTouchingCession() {
        UUID taskId = UUID.randomUUID();
        UUID cessionId = UUID.randomUUID();
        ReinsuranceReviewTask task = new ReinsuranceReviewTask();
        task.setId(taskId);
        task.setStatus("OPEN");
        task.setCessionId(cessionId);
        when(repository.findById(taskId)).thenReturn(Mono.just(task));
        when(repository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(service.resolve(taskId, "DISMISSED", "false positive", "sys", "sys@test"))
                .assertNext(resp -> assertThat(resp.status()).isEqualTo("DISMISSED"))
                .verifyComplete();

        verify(cessionRepository, never()).findById(any(UUID.class));
    }

    @Test
    void resolve_invalidResolution_errors() {
        StepVerifier.create(service.resolve(UUID.randomUUID(), "MAYBE", null, "sys", "sys@test"))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    void resolve_alreadyResolved_errors() {
        UUID taskId = UUID.randomUUID();
        ReinsuranceReviewTask task = new ReinsuranceReviewTask();
        task.setId(taskId);
        task.setStatus("RESOLVED_KEEP");
        when(repository.findById(taskId)).thenReturn(Mono.just(task));

        StepVerifier.create(service.resolve(taskId, "DISMISSED", null, "sys", "sys@test"))
                .expectError(IllegalStateException.class)
                .verify();
    }

    private static Cession cession(UUID id, String basis, String ceded) {
        Cession c = new Cession();
        c.setId(id);
        c.setTreatyId(UUID.randomUUID());
        c.setCessionType("LOSS");
        c.setSource("AUTOMATIC");
        c.setStatus("ACTIVE");
        c.setBasisAmount(new BigDecimal(basis));
        c.setCededAmount(new BigDecimal(ceded));
        c.setCurrencyCode("USD");
        c.setSourceEventId(UUID.randomUUID());
        return c;
    }
}

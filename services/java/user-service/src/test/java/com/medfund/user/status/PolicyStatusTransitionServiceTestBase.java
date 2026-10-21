package com.medfund.user.status;

import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.lifecycle.StatusTransitionRecorder;
import com.medfund.user.publisher.PolicyStatusChangedPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.server.ResponseStatusException;
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
 * Uniform-contract coverage for the Phase 13 §A policy pathway (per L5):
 * every {@link PolicyStatusTransitionService} subclass must behave exactly
 * the same on this matrix regardless of line. Per-line reason-vocab guards
 * live in the concrete subclasses.
 *
 * <p>The transaction operator is stubbed to an identity pass-through; real
 * transactionality is proven by the member-pathway IT pattern against real
 * Postgres (Phase 2) and by {@code PolicyStatusHistoryWritesIT}.
 */
@ExtendWith(MockitoExtension.class)
abstract class PolicyStatusTransitionServiceTestBase<T> {

    @Mock protected StatusTransitionRecorder recorder;
    @Mock protected AuditPublisher auditPublisher;
    @Mock protected PolicyStatusChangedPublisher policyStatusChangedPublisher;
    @Mock protected TransactionalOperator tx;

    protected UUID policyId;
    protected final String actorId = UUID.randomUUID().toString();
    protected final String actorEmail = "underwriter@test.example";

    /** Concrete repository mock — typed per line. */
    protected abstract R2dbcRepository<T, UUID> repository();

    protected abstract PolicyStatusTransitionService<T> service();

    /** Fresh entity in the given status with id/name populated. */
    protected abstract T entity(String status);

    /**
     * A code from ANOTHER line's vocab — must be rejected by this line's
     * service with 400 before any DB work (e.g. MORTALITY on travel).
     */
    protected abstract String foreignReasonCode();

    @BeforeEach
    void baseSetUp() {
        policyId = UUID.randomUUID();
        lenient().when(tx.transactional(ArgumentMatchers.<Mono<T>>any()))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        lenient().when(recorder.recordPolicy(any(), anyString(), anyString(), anyString(),
                        any(OffsetDateTime.class), any(), anyString(), any(), any()))
                .thenReturn(Mono.empty());
        lenient().when(policyStatusChangedPublisher.publish(any(), any(), anyString(), anyString(),
                        any(), anyString(), any(OffsetDateTime.class), any(), any(), anyString()))
                .thenReturn(Mono.empty());
    }

    @Test
    void transition_activeToLapsed_writesHistoryRow_savesEntity_publishesEvent() {
        T existing = entity("active");
        when(repository().findById(policyId)).thenReturn(Mono.just(existing));
        when(repository().save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service().transition(policyId, "lapse", "NON_PAYMENT",
                        "premium overdue", actorId, actorEmail))
                .assertNext(saved -> assertThat(service().getStatus(saved)).isEqualTo("lapsed"))
                .verifyComplete();

        verify(recorder).recordPolicy(eq(policyId), eq(policySource()), eq("active"), eq("lapsed"),
                any(OffsetDateTime.class), any(), eq(actorEmail), eq("NON_PAYMENT"), eq("premium overdue"));
        verify(repository()).save(any());
        verify(auditPublisher).publish(any());
        // Phase 13 §B: policy-status-changed event fires after audit publish.
        verify(policyStatusChangedPublisher).publish(
                any(), eq(policyId), eq(policySource()), eq(insuranceLineExpected()),
                eq("active"), eq("lapsed"), any(OffsetDateTime.class),
                eq("NON_PAYMENT"), eq(actorId), eq(actorEmail));
    }

    @Test
    void transition_sameStatus_isNoop_noHistoryRowNoSave() {
        T existing = entity("lapsed");
        when(repository().findById(policyId)).thenReturn(Mono.just(existing));

        StepVerifier.create(service().transition(policyId, "lapse", "NON_PAYMENT", null,
                        actorId, actorEmail))
                .assertNext(saved -> assertThat(service().getStatus(saved)).isEqualTo("lapsed"))
                .verifyComplete();

        verify(repository(), never()).save(any());
        verifyNoInteractions(recorder, auditPublisher, policyStatusChangedPublisher);
    }

    @Test
    void transition_unknownPolicy_returns404() {
        when(repository().findById(policyId)).thenReturn(Mono.empty());

        StepVerifier.create(service().transition(policyId, "terminate", "ADMIN_CORRECTION", null,
                        actorId, actorEmail))
                .expectErrorSatisfies(e ->
                        assertThat(((ResponseStatusException) e).getStatusCode())
                                .isEqualTo(HttpStatus.NOT_FOUND))
                .verify();

        verifyNoInteractions(recorder, auditPublisher, policyStatusChangedPublisher);
    }

    @Test
    void transition_reasonCodeFromAnotherLine_isRejected400_beforeAnyDbWork() {
        StepVerifier.create(service().transition(policyId, "suspend", foreignReasonCode(), null,
                        actorId, actorEmail))
                .expectErrorSatisfies(e -> {
                    var rse = (ResponseStatusException) e;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(rse.getReason()).contains(policySource());
                })
                .verify();

        verifyNoInteractions(repository(), recorder, auditPublisher, policyStatusChangedPublisher);
    }

    @Test
    void transition_recorderFails_entitySaveNeverHappens_noEventPublished() {
        T existing = entity("active");
        when(repository().findById(policyId)).thenReturn(Mono.just(existing));
        when(recorder.recordPolicy(any(), anyString(), anyString(), anyString(),
                any(OffsetDateTime.class), any(), anyString(), any(), any()))
                .thenReturn(Mono.error(new RuntimeException("history insert failed")));

        // Deferred-save ordering guard: a failed record must not even build
        // the update, let alone commit it. ADMIN_CORRECTION is valid on every
        // line so the request passes vocab validation and reaches the recorder.
        StepVerifier.create(service().transition(policyId, "terminate", "ADMIN_CORRECTION", null,
                        actorId, actorEmail))
                .expectErrorMessage("history insert failed")
                .verify();

        verify(repository(), never()).save(any());
        // Phase 13 §B: no Kafka event when the transaction fails.
        verifyNoInteractions(policyStatusChangedPublisher);
    }

    @Test
    void transition_unknownAction_returns400() {
        StepVerifier.create(service().transition(policyId, "explode", null, null,
                        actorId, actorEmail))
                .expectErrorSatisfies(e ->
                        assertThat(((ResponseStatusException) e).getStatusCode())
                                .isEqualTo(HttpStatus.BAD_REQUEST))
                .verify();

        verifyNoInteractions(repository(), recorder, auditPublisher, policyStatusChangedPublisher);
    }

    @Test
    void transition_reinstate_lapsedToActive() {
        T existing = entity("lapsed");
        when(repository().findById(policyId)).thenReturn(Mono.just(existing));
        when(repository().save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service().transition(policyId, "reinstate", "ADMIN_CORRECTION",
                        "paid in full", actorId, actorEmail))
                .assertNext(saved -> assertThat(service().getStatus(saved)).isEqualTo("active"))
                .verifyComplete();

        verify(recorder).recordPolicy(eq(policyId), eq(policySource()), eq("lapsed"), eq("active"),
                any(OffsetDateTime.class), any(), eq(actorEmail), eq("ADMIN_CORRECTION"), any());
    }

    @Test
    void transition_actorEmailMissing_isRejected400() {
        StepVerifier.create(service().transition(policyId, "lapse", null, null, actorId, null))
                .expectErrorSatisfies(e ->
                        assertThat(((ResponseStatusException) e).getStatusCode())
                                .isEqualTo(HttpStatus.BAD_REQUEST))
                .verify();
        StepVerifier.create(service().transition(policyId, "lapse", null, null, actorId, "  "))
                .expectErrorSatisfies(e ->
                        assertThat(((ResponseStatusException) e).getStatusCode())
                                .isEqualTo(HttpStatus.BAD_REQUEST))
                .verify();

        verifyNoInteractions(repository(), recorder, auditPublisher, policyStatusChangedPublisher);
    }

    protected abstract String policySource();

    /** Insurance-line name expected on the policy-status-changed event (LIFE / VEHICLE / …). */
    protected abstract String insuranceLineExpected();
}

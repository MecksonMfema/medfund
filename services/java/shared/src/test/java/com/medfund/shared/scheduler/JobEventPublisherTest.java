package com.medfund.shared.scheduler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.shared.notification.Notification;
import com.medfund.shared.notification.NotificationRecipientResolver;
import com.medfund.shared.notification.NotificationWriter;
import com.medfund.shared.notification.NotificationRepository;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderRecord;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Contract test for the {@code medfund.jobs.completed} wire payload. The
 * notification-service on the other side of the topic prefers
 * {@code triggeredByEmail} over its local {@code public.staff_users} lookup —
 * emails silently stalled for every commit-completed notification in the
 * 2026-07-02 outage because the row wasn't in staff_users. Piping the email
 * from the JWT through the run row into this event payload closed that gap.
 *
 * <p>These tests pin (a) the field name / JSON shape and (b) the null-safe
 * empty-string default so downstream consumers can rely on a stable schema.
 */
@ExtendWith(MockitoExtension.class)
class JobEventPublisherTest {

    @Mock
    private KafkaSender<String, String> kafkaSender;

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private NotificationRecipientResolver recipientResolver;

    @Captor
    private ArgumentCaptor<Mono<SenderRecord<String, String, String>>> senderCaptor;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private JobEventPublisher publisher;

    @BeforeEach
    void setUp() {
        // NotificationWriter is a thin wrapper — construct it against the
        // mocked repository so the test exercises the same wiring the
        // production DI graph will produce. Save calls return the argument
        // unchanged so the reactive chain completes.
        lenient().when(notificationRepository.save(any(Notification.class)))
            .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        lenient().when(notificationRepository.findBySource(any(), any(), any()))
            .thenReturn(Mono.empty());
        // Recipient resolver is only exercised by the scheduled-fan-out path;
        // the JSON-payload tests use manual triggers, so returning empty is
        // safe and keeps this suite focused on the wire shape.
        lenient().when(recipientResolver.forPermissions(any(), any()))
            .thenReturn(reactor.core.publisher.Flux.empty());
        NotificationWriter writer = new NotificationWriter(notificationRepository);
        publisher = new JobEventPublisher(kafkaSender, objectMapper, writer, recipientResolver);
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishJobCompleted_populatedRun_carriesTriggeredByEmail() {
        when(kafkaSender.send(any(Mono.class))).thenReturn(Flux.empty());
        ScheduledJobRun run = baseRun(UUID.randomUUID());
        run.setTriggeredBy(UUID.randomUUID());
        run.setTriggeredByEmail("admin@medfund.com");
        run.setStatus("SUCCESS");

        StepVerifier.create(publisher.publishJobCompleted(run, "BILLING_COMMIT"))
                .verifyComplete();

        JsonNode payload = capturePayload();
        assertThat(payload.path("event").asText()).isEqualTo("JOB_COMPLETED");
        assertThat(payload.path("kind").asText()).isEqualTo("BILLING_COMMIT");
        assertThat(payload.path("triggeredBy").asText())
                .isEqualTo(run.getTriggeredBy().toString());
        assertThat(payload.path("triggeredByEmail").asText())
                .as("email must land verbatim on the wire so notification-service can skip staff_users")
                .isEqualTo("admin@medfund.com");
        assertThat(payload.path("status").asText()).isEqualTo("SUCCESS");
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishJobCompleted_nullEmail_serialisesAsEmptyStringNotNull() {
        // Scheduled / system jobs have no human actor → email is null. The
        // publisher must emit "" rather than JSON null so consumers can treat
        // "empty" as the single fallback signal instead of branching on
        // undefined-vs-null.
        when(kafkaSender.send(any(Mono.class))).thenReturn(Flux.empty());
        ScheduledJobRun run = baseRun(UUID.randomUUID());
        run.setTriggeredBy(null);
        run.setTriggeredByEmail(null);
        run.setStatus("SUCCESS");

        StepVerifier.create(publisher.publishJobCompleted(run, "OVERDUE_CHECK"))
                .verifyComplete();

        JsonNode payload = capturePayload();
        assertThat(payload.has("triggeredByEmail"))
                .as("field must always be present in the payload")
                .isTrue();
        assertThat(payload.path("triggeredByEmail").asText())
                .as("null → empty string per the nullSafe convention")
                .isEmpty();
        assertThat(payload.path("triggeredByEmail").isNull())
                .as("must be a JSON string (\"\"), not JSON null")
                .isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishJobCompleted_failedJob_stillCarriesEmailForFailureAlert() {
        // Failure alerts to the actor were part of the original commit-completed
        // pipeline — an operator who triggered a run that later failed still
        // needs the "your job failed" mail. Guard here so a future refactor
        // that special-cases FAILED doesn't drop the email.
        when(kafkaSender.send(any(Mono.class))).thenReturn(Flux.empty());
        ScheduledJobRun run = baseRun(UUID.randomUUID());
        run.setTriggeredBy(UUID.randomUUID());
        run.setTriggeredByEmail("finance-lead@medfund.com");
        run.setStatus("FAILED");
        run.setErrorMessage("Constraint violation: contributions_period_unique");

        StepVerifier.create(publisher.publishJobCompleted(run, "BILLING_COMMIT"))
                .verifyComplete();

        JsonNode payload = capturePayload();
        assertThat(payload.path("status").asText()).isEqualTo("FAILED");
        assertThat(payload.path("triggeredByEmail").asText())
                .isEqualTo("finance-lead@medfund.com");
        assertThat(payload.path("errorMessage").asText())
                .contains("contributions_period_unique");
    }

    // ── Fan-out for scheduled runs ──────────────────────────────────────

    /**
     * Scheduled billing runs must notify every user in the tenant with
     * one of the billing:* permissions — the audience the requirement
     * calls out. This guards the recipient-resolver hookup + the
     * per-user write inside {@code fanOut}.
     */
    @Test
    @SuppressWarnings("unchecked")
    void publishJobCompleted_scheduledBilling_fansOutToPermissionHolders() {
        when(kafkaSender.send(any(Mono.class))).thenReturn(reactor.core.publisher.Flux.empty());
        UUID u1 = UUID.randomUUID();
        UUID u2 = UUID.randomUUID();
        UUID u3 = UUID.randomUUID();
        when(recipientResolver.forPermissions(any(),
                org.mockito.ArgumentMatchers.argThat(list ->
                        list.contains("billing:generate_billing") && list.contains("billing:view"))))
                .thenReturn(reactor.core.publisher.Flux.just(u1, u2, u3));

        ScheduledJobRun run = baseRun(UUID.randomUUID());
        run.setTriggerKind("schedule");
        run.setTriggeredBy(null);
        run.setStatus("SUCCESS");

        StepVerifier.create(publisher.publishJobCompleted(run, "BILLING_COMMIT"))
                .verifyComplete();

        // One notification row per user with a matching permission.
        ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, org.mockito.Mockito.times(3)).save(saved.capture());
        java.util.List<UUID> recipients = saved.getAllValues().stream()
                .map(Notification::getUserId).toList();
        assertThat(recipients).containsExactlyInAnyOrder(u1, u2, u3);
        assertThat(saved.getAllValues().get(0).getTitle()).contains("Scheduled");
        assertThat(saved.getAllValues().get(0).getKind()).isEqualTo("JOB_COMPLETED");
    }

    /**
     * On failure the fan-out still fires, severity flips to ERROR, and
     * the error message rides on the body so the audience can triage
     * without opening the run row.
     */
    @Test
    @SuppressWarnings("unchecked")
    void publishJobCompleted_scheduledFailure_fansOutWithErrorBody() {
        when(kafkaSender.send(any(Mono.class))).thenReturn(reactor.core.publisher.Flux.empty());
        UUID u1 = UUID.randomUUID();
        when(recipientResolver.forPermissions(any(), any()))
                .thenReturn(reactor.core.publisher.Flux.just(u1));

        ScheduledJobRun run = baseRun(UUID.randomUUID());
        run.setTriggerKind("schedule");
        run.setStatus("FAILED");
        run.setErrorMessage("Postgres connection refused");

        StepVerifier.create(publisher.publishJobCompleted(run, "BILLING_PREVIEW"))
                .verifyComplete();

        ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(saved.capture());
        Notification n = saved.getValue();
        assertThat(n.getUserId()).isEqualTo(u1);
        assertThat(n.getSeverity()).isEqualTo("error");
        assertThat(n.getBody()).isEqualTo("Postgres connection refused");
        assertThat(n.getTitle()).contains("failed");
    }

    /**
     * Manual runs keep the legacy actor-only routing — no fan-out. The
     * resolver must not be consulted, and only the JWT-subject user gets
     * a row.
     */
    @Test
    @SuppressWarnings("unchecked")
    void publishJobCompleted_manualBilling_notifiesActorOnly() {
        when(kafkaSender.send(any(Mono.class))).thenReturn(reactor.core.publisher.Flux.empty());
        UUID actor = UUID.randomUUID();

        ScheduledJobRun run = baseRun(UUID.randomUUID());
        run.setTriggerKind("manual");
        run.setTriggeredBy(actor);
        run.setStatus("SUCCESS");

        StepVerifier.create(publisher.publishJobCompleted(run, "BILLING_COMMIT"))
                .verifyComplete();

        // Actor got exactly one row; resolver was never asked.
        ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(actor);
        assertThat(saved.getValue().getTitle()).contains("Your");
        org.mockito.Mockito.verify(recipientResolver, org.mockito.Mockito.never())
                .forPermissions(any(), any());
    }

    /**
     * publishJobStarted is the new "started" hook — must fire for
     * scheduled runs and silently no-op for manual ones (the operator
     * who clicked already knows they clicked).
     */
    @Test
    void publishJobStarted_manualRun_writesNothing() {
        ScheduledJobRun run = baseRun(UUID.randomUUID());
        run.setTriggerKind("manual");
        run.setTriggeredBy(UUID.randomUUID());
        run.setStatus("RUNNING");

        StepVerifier.create(publisher.publishJobStarted(run, "BILLING_COMMIT"))
                .verifyComplete();

        org.mockito.Mockito.verify(notificationRepository, org.mockito.Mockito.never())
                .save(any(Notification.class));
        org.mockito.Mockito.verify(recipientResolver, org.mockito.Mockito.never())
                .forPermissions(any(), any());
    }

    @Test
    void publishJobStarted_scheduledBilling_writesInfoRowPerRecipient() {
        UUID u1 = UUID.randomUUID();
        UUID u2 = UUID.randomUUID();
        when(recipientResolver.forPermissions(any(), any()))
                .thenReturn(reactor.core.publisher.Flux.just(u1, u2));

        ScheduledJobRun run = baseRun(UUID.randomUUID());
        run.setTriggerKind("schedule");
        run.setStatus("RUNNING");

        StepVerifier.create(publisher.publishJobStarted(run, "BILLING_COMMIT"))
                .verifyComplete();

        ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, org.mockito.Mockito.times(2)).save(saved.capture());
        saved.getAllValues().forEach(n -> {
            assertThat(n.getKind()).isEqualTo("JOB_STARTED");
            assertThat(n.getSeverity()).isEqualTo("info");
            assertThat(n.getTitle()).contains("Scheduled").contains("started");
        });
    }

    /**
     * Job kinds outside the billing family don't have an audience —
     * scheduled runs of an OVERDUE_CHECK today produce zero rows.
     * Adding a new audience is a switch-branch change; this test
     * documents the current mapping.
     */
    @Test
    void publishJobStarted_scheduledNonBilling_noAudience_noWrites() {
        ScheduledJobRun run = baseRun(UUID.randomUUID());
        run.setTriggerKind("schedule");
        run.setStatus("RUNNING");

        StepVerifier.create(publisher.publishJobStarted(run, "OVERDUE_CHECK"))
                .verifyComplete();

        org.mockito.Mockito.verify(notificationRepository, org.mockito.Mockito.never())
                .save(any(Notification.class));
        org.mockito.Mockito.verify(recipientResolver, org.mockito.Mockito.never())
                .forPermissions(any(), any());
    }

    private JsonNode capturePayload() {
        verify(kafkaSender).send(senderCaptor.capture());
        SenderRecord<String, String, String> record =
                senderCaptor.getValue().block();
        assertThat(record).isNotNull();
        ProducerRecord<String, String> producerRecord = record;
        assertThat(producerRecord.topic()).isEqualTo("medfund.jobs.completed");
        try {
            return objectMapper.readTree(producerRecord.value());
        } catch (Exception e) {
            throw new AssertionError("failed to parse serialised payload", e);
        }
    }

    private ScheduledJobRun baseRun(UUID runId) {
        ScheduledJobRun run = new ScheduledJobRun();
        run.setId(runId);
        run.setConfigId(UUID.randomUUID());
        run.setTenantId(UUID.randomUUID());
        run.setStartedAt(Instant.now().minusSeconds(30));
        run.setEndedAt(Instant.now());
        run.setDurationMs(30_000L);
        run.setTriggerKind("manual");
        return run;
    }
}

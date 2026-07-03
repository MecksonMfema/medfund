package com.medfund.shared.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.shared.notification.NotificationRecipientResolver;
import com.medfund.shared.notification.NotificationSeverity;
import com.medfund.shared.notification.NotificationWriter;
import com.medfund.shared.notification.NotificationWriter.NewNotification;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderRecord;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Publishes a JobCompleted event once per terminal job state (SUCCESS or
 * FAILED) to {@code medfund.jobs.completed}. notification-service consumes
 * this to email the triggering user when a long-running job finishes; the
 * Angular header tray consumes it (indirectly, via a REST endpoint) to
 * show in-app progress.
 *
 * <p>Fire-and-forget by design — a flaky Kafka must not poison the job
 * pipeline. Errors are logged; the run itself stays committed because
 * {@link JobDispatcher} writes the SUCCESS/FAILED row before publish.
 */
@Component
public class JobEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(JobEventPublisher.class);
    private static final String TOPIC = "medfund.jobs.completed";

    private final KafkaSender<String, String> kafkaSender;
    private final ObjectMapper objectMapper;
    private final NotificationWriter notificationWriter;
    private final NotificationRecipientResolver recipientResolver;

    /**
     * Permissions that gate the fan-out audience for scheduled billing
     * jobs. Any user in the tenant with at least one of these gets a
     * start / success / failure bell row. Kept as a constant here (not
     * per-job configurable) — a new job kind that wants a different
     * audience adds a switch branch in {@link #permissionsFor(String)}.
     */
    private static final List<String> BILLING_AUDIENCE =
            List.of("billing:generate_billing", "billing:view");

    public JobEventPublisher(KafkaSender<String, String> kafkaSender,
                             ObjectMapper objectMapper,
                             NotificationWriter notificationWriter,
                             NotificationRecipientResolver recipientResolver) {
        this.kafkaSender = kafkaSender;
        this.objectMapper = objectMapper;
        this.notificationWriter = notificationWriter;
        this.recipientResolver = recipientResolver;
    }

    /**
     * Emit a JobCompleted event for the supplied run + config. Every field
     * is optional on the wire — consumers should treat missing values as
     * "unknown" rather than reject the payload. {@code kind} is the
     * machine-readable job-type identifier (e.g. {@code BILLING_COMMIT}).
     */
    public Mono<Void> publishJobCompleted(ScheduledJobRun run, String kind) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("event", "JOB_COMPLETED");
        payload.put("runId",        nullSafe(run.getId()));
        payload.put("configId",     nullSafe(run.getConfigId()));
        payload.put("tenantId",     nullSafe(run.getTenantId()));
        payload.put("kind",         nullSafe(kind));
        payload.put("status",       nullSafe(run.getStatus()));
        payload.put("triggerKind",  nullSafe(run.getTriggerKind()));
        payload.put("triggeredBy",  nullSafe(run.getTriggeredBy()));
        payload.put("triggeredByEmail", nullSafe(run.getTriggeredByEmail()));
        payload.put("startedAt",    nullSafe(run.getStartedAt()));
        payload.put("endedAt",      nullSafe(run.getEndedAt()));
        payload.put("durationMs",   run.getDurationMs() == null ? "" : run.getDurationMs().toString());
        payload.put("resultPayload", nullSafe(run.getResultPayload()));
        payload.put("errorMessage", nullSafe(run.getErrorMessage()));

        try {
            String json = objectMapper.writeValueAsString(payload);
            String key = run.getId() != null ? run.getId().toString() : "unknown";
            var record = new ProducerRecord<>(TOPIC, key, json);
            Mono<Void> kafkaSend = kafkaSender.send(Mono.just(SenderRecord.create(record, key)))
                    .doOnError(e -> log.warn("Failed to publish JobCompleted for run {}: {}",
                            key, e.getMessage()))
                    .then();
            // Persist an in-app notification row for the triggering user so
            // the Angular bell reflects the completion even after the
            // localStorage side of the old model disappears. Kafka publish
            // and notification write run in parallel — the notification is
            // best-effort so a Postgres blip doesn't hold up email dispatch.
            return Mono.when(kafkaSend, writeBellNotification(run, kind));
        } catch (Exception e) {
            log.warn("Failed to serialize JobCompleted for run {}: {}", run.getId(), e.getMessage());
            return Mono.empty();
        }
    }

    /**
     * Emit the "job started" bell notifications. Only fires for
     * <em>scheduled</em> runs (the operator who clicked Run Now
     * doesn't need to be told their own click landed) and only for
     * job kinds with an audience defined via {@link #permissionsFor}.
     *
     * <p>Called by {@link JobDispatcher} right after the RUNNING run
     * row is persisted. Best-effort — a Postgres blip must not
     * prevent the job itself from executing.
     */
    public Mono<Void> publishJobStarted(ScheduledJobRun run, String kind) {
        if (!"schedule".equals(run.getTriggerKind())) return Mono.empty();
        List<String> audience = permissionsFor(kind);
        if (audience.isEmpty()) return Mono.empty();
        String friendly = friendlyKind(kind);
        String title = "Scheduled " + friendly + " started";
        String body = "Automatic run kicked off at " +
                (run.getStartedAt() != null ? run.getStartedAt().toString() : "now") + ".";
        return fanOut(run, kind, audience, "JOB_STARTED", title, body, NotificationSeverity.INFO);
    }

    /**
     * Bell-row for a completed run. Routing depends on trigger kind:
     * manual runs notify the actor who clicked; scheduled runs fan out
     * to every tenant user who holds a permission in
     * {@link #permissionsFor(String)}. Silent no-op for job kinds that
     * don't have a fan-out audience defined AND weren't triggered by a
     * human — those are pure system chores nobody needs to see.
     */
    private Mono<Void> writeBellNotification(ScheduledJobRun run, String jobKind) {
        String severity = "SUCCESS".equals(run.getStatus())
                ? NotificationSeverity.SUCCESS
                : NotificationSeverity.ERROR;
        String friendly = friendlyKind(jobKind);

        if ("schedule".equals(run.getTriggerKind())) {
            List<String> audience = permissionsFor(jobKind);
            if (audience.isEmpty()) return Mono.empty();
            String title = "SUCCESS".equals(run.getStatus())
                    ? "Scheduled " + friendly + " finished"
                    : "Scheduled " + friendly + " failed";
            String body = "SUCCESS".equals(run.getStatus())
                    ? null
                    : run.getErrorMessage();
            return fanOut(run, jobKind, audience, "JOB_COMPLETED", title, body, severity);
        }

        // Manual path — notify the actor who clicked.
        UUID actor = run.getTriggeredBy();
        if (actor == null) return Mono.empty();
        String title = "SUCCESS".equals(run.getStatus())
                ? "Your " + friendly + " finished"
                : "Your " + friendly + " failed";
        String body = "SUCCESS".equals(run.getStatus())
                ? null
                : run.getErrorMessage();
        return notificationWriter.write(new NewNotification(
                run.getTenantId(),
                actor,
                "JOB_COMPLETED",
                title,
                body,
                severity,
                "scheduled_job_run",
                run.getId(),
                null,
                null
        )).doOnNext(saved -> log.debug("Wrote bell notification for actor {} run {}",
                actor, run.getId())).then();
    }

    /**
     * Write one notification row per user in the tenant with any of
     * {@code audience} permissions. Uses the recipient resolver to
     * expand the audience via {@code user_roles ⨝ role_permissions}
     * against the tenant schema — an empty result set silently writes
     * zero rows.
     */
    private Mono<Void> fanOut(ScheduledJobRun run, String jobKind,
                              List<String> audience, String notificationKind,
                              String title, String body, String severity) {
        String tenantId = run.getTenantId() != null ? run.getTenantId().toString() : null;
        return recipientResolver.forPermissions(tenantId, audience)
                .flatMap(userId -> notificationWriter.write(new NewNotification(
                        run.getTenantId(),
                        userId,
                        notificationKind,
                        title,
                        body,
                        severity,
                        "scheduled_job_run",
                        run.getId(),
                        null,
                        null
                )))
                .doOnComplete(() -> log.info("Fanned out {} notifications for scheduled {} run {}",
                        notificationKind, jobKind, run.getId()))
                .then();
    }

    /**
     * Audience for a given job kind — the list of permission strings
     * that grant a user a bell row on that kind's scheduled runs. An
     * empty list means "no fan-out"; add a new switch branch to
     * broaden the coverage without touching the fan-out plumbing.
     */
    private static List<String> permissionsFor(String jobKind) {
        if (jobKind == null) return List.of();
        return switch (jobKind) {
            case "BILLING_PREVIEW", "BILLING_COMMIT", "BILLING_CYCLE" -> BILLING_AUDIENCE;
            default -> List.of();
        };
    }

    /** Mirror of notification-service's job.friendlyKind so the wording
     *  is consistent between email, audit log, and bell. */
    private static String friendlyKind(String k) {
        if (k == null) return "job";
        return switch (k) {
            case "BILLING_COMMIT"  -> "billing commit";
            case "BILLING_PREVIEW" -> "billing preview";
            case "BILLING_CYCLE"   -> "billing cycle";
            case "OVERDUE_CHECK"   -> "overdue check";
            default -> "job";
        };
    }

    private static String nullSafe(Object v) {
        return v == null ? "" : v.toString();
    }
}

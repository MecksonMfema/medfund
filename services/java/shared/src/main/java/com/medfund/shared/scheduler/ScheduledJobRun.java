package com.medfund.shared.scheduler;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One row per job execution. Persisted by JobDispatcher (for cron-driven
 * runs) and by ScheduledJobController.runNow (for manual retriggers). The
 * platform admin job monitor reads from this table to surface success /
 * failure history and per-run errors.
 */
@Getter
@Setter
@Table("scheduled_job_runs")
public class ScheduledJobRun {

    @Id
    private UUID id;

    @Column("config_id")
    private UUID configId;

    /**
     * Tenant the run belongs to. Stamped from the config row at start;
     * {@code null} for platform-global jobs.
     */
    @Column("tenant_id")
    private UUID tenantId;

    @Column("started_at")
    private Instant startedAt;

    @Column("ended_at")
    private Instant endedAt;

    @Column("duration_ms")
    private Long durationMs;

    /** RUNNING → SUCCESS or FAILED. */
    private String status = "RUNNING";

    /** schedule | manual */
    @Column("trigger_kind")
    private String triggerKind = "schedule";

    @Column("error_message")
    private String errorMessage;

    @Column("triggered_by")
    private UUID triggeredBy;

    /**
     * Actor email captured at job-enqueue time from the caller's JWT. Threaded
     * into JobCompleted so notification-service doesn't have to convert
     * {@link #triggeredBy} UUID → email via a staff_users lookup that requires
     * a locally-provisioned row (and silently dropped every commit-completed
     * email when the row wasn't there). Null for scheduled / system jobs.
     */
    @Column("triggered_by_email")
    private String triggeredByEmail;

    /**
     * Optional JSON payload an executor hands back on success — e.g. billing
     * preview totals, commit counts. Stored as TEXT with a CHECK(IS JSON);
     * see V116 for the storage rationale. {@code null} for jobs that don't
     * produce a payload (the existing 6 default executors). Polled by the UI
     * after triggering an ad-hoc job to surface the result.
     */
    @Column("result_payload")
    private String resultPayload;

    @CreatedDate
    @Column("created_at")
    private Instant createdAt;
}

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

    @CreatedDate
    @Column("created_at")
    private Instant createdAt;
}

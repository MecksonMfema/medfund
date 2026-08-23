package com.medfund.contributions.premium.entity;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Progress record for a {@code PremiumEarningExecutor} pass (grill note 5).
 * A row is created when a run starts and finished when it ends; heartbeats
 * are updated per-chunk so a cancel/resume path can tell whether an earlier
 * run is still alive or safely resumable.
 */
@Getter
@Setter
@Table("earning_schedule_run")
public class EarningScheduleRun {

    @Id
    private UUID id;

    @Column("tenant_id")
    private UUID tenantId;

    /** {@code SCHEDULED}, {@code BACKFILL}, or {@code ENDORSEMENT_RECOMPUTE}. */
    @Column("run_kind")
    private String runKind;

    /** {@code policyId} for a backfill, {@code endorsementId} for a recompute; null for scheduled. */
    @Column("trigger_reference")
    private UUID triggerReference;

    /** {@code PENDING}, {@code RUNNING}, {@code COMPLETED}, {@code CANCELLED}, {@code FAILED}. */
    private String status;

    @Column("started_at")
    private Instant startedAt;

    @Column("finished_at")
    private Instant finishedAt;

    @Column("last_heartbeat_at")
    private Instant lastHeartbeatAt;

    @Column("last_processed_policy_id")
    private UUID lastProcessedPolicyId;

    @Column("policies_processed")
    private int policiesProcessed;

    @Column("periods_written")
    private int periodsWritten;

    @Column("error_message")
    private String errorMessage;
}

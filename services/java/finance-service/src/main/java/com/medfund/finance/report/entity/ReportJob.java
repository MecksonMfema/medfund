package com.medfund.finance.report.entity;

import io.r2dbc.postgresql.codec.Json;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Durable job/result row for the async Kafka report pipeline (Phase 15 §1 —
 * renamed from Phase-14 {@code ActuarialReportJob}). finance-service inserts
 * the row on submit and publishes to {@code medfund.report.job-requested};
 * the ai-service result consumer terminal-writes {@link #status},
 * {@link #resultJson}, and {@link #completedAt}. The V151 append-only trigger
 * prevents rewrite after a terminal status lands.
 *
 * <p>{@link #retentionClass} drives the {@code ReportJobRetentionJob} (Phase 3):
 * {@code STATUTORY_7Y} for IFRS 17 / REGULATORY family keys, {@code OPERATIONAL_90D}
 * for actuarial / operational keys. {@link #parentJobId} links a chunk row to
 * its parent for the IFRS 17 aggregator pattern (§18) and the IBNR sub-job
 * orchestrator (§24) — null for standalone jobs (all Phase-14 actuarial rows).
 */
@Getter
@Setter
@NoArgsConstructor
@Table("report_job")
public class ReportJob {

    public static final String RETENTION_OPERATIONAL_90D = "OPERATIONAL_90D";
    public static final String RETENTION_STATUTORY_7Y = "STATUTORY_7Y";

    @Id
    @Column("job_id")
    private UUID jobId;

    @Column("tenant_id")
    private UUID tenantId;

    @Column("parent_job_id")
    private UUID parentJobId;

    @Column("report_key")
    private String reportKey;

    private String status;

    @Column("params_json")
    private Json paramsJson;

    @Column("params_hash")
    private String paramsHash;

    @Column("result_json")
    private Json resultJson;

    @Column("error_message")
    private String errorMessage;

    @Column("requested_at")
    private OffsetDateTime requestedAt;

    @Column("completed_at")
    private OffsetDateTime completedAt;

    @Column("requested_by")
    private UUID requestedBy;

    @Column("requested_by_email")
    private String requestedByEmail;

    @Column("retention_class")
    private String retentionClass;
}

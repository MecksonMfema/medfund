package com.medfund.finance.actuarial.entity;

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
 * Durable job/result row for the async Kafka actuarial pipeline (Phase 14 §A/B).
 * finance-service inserts the row on submit and publishes to
 * {@code medfund.actuarial.job-requested}; the ai-service result consumer
 * terminal-writes {@link #status}, {@link #resultJson}, and {@link #completedAt}.
 * The V141 append-only trigger prevents rewrite after a terminal status lands.
 */
@Getter
@Setter
@NoArgsConstructor
@Table("actuarial_report_job")
public class ActuarialReportJob {

    @Id
    @Column("job_id")
    private UUID jobId;

    @Column("tenant_id")
    private UUID tenantId;

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
}

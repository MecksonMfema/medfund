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
 * A single portfolio × cohort × currency fan-out row for an IFRS 17 async
 * report (Phase 15 §3 — I14 chunking). Its parent {@link ReportJob} is
 * inserted at submit time; the shaping service (§17) then inserts one
 * chunk per (portfolio, cohort, currency) tuple and publishes one
 * {@code medfund.report.job-requested} event per chunk. ai-service compute
 * writes the terminal status via the shared result consumer, and the
 * aggregator (§18) rolls chunks up into the parent's {@code result_json}
 * once every chunk is terminal.
 *
 * <p>{@link #paramsRef} / {@link #resultRef} carry the {@code s3://…}
 * pointer when the JSON payload exceeds the 900KB Kafka ceiling (§10 MinIO
 * fallback). When set, the inline {@code paramsJson} / {@code resultJson}
 * columns hold {@code null}.
 *
 * <p>The parent {@code parent_job_id} FK cascades on delete, so
 * {@link com.medfund.finance.report.scheduler.ReportJobRetentionJob}
 * purges chunks together with their parent without a second query.
 */
@Getter
@Setter
@NoArgsConstructor
@Table("report_job_chunk")
public class ReportJobChunk {

    @Id
    @Column("chunk_id")
    private UUID chunkId;

    @Column("parent_job_id")
    private UUID parentJobId;

    @Column("portfolio_id")
    private UUID portfolioId;

    @Column("cohort_id")
    private UUID cohortId;

    private String currency;

    private String status;

    @Column("params_json")
    private Json paramsJson;

    @Column("params_ref")
    private String paramsRef;

    @Column("result_json")
    private Json resultJson;

    @Column("result_ref")
    private String resultRef;

    @Column("error_message")
    private String errorMessage;

    @Column("requested_at")
    private OffsetDateTime requestedAt;

    @Column("completed_at")
    private OffsetDateTime completedAt;
}

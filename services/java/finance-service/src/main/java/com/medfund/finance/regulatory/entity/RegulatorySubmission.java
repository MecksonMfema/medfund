package com.medfund.finance.regulatory.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Tenant-scoped row from {@code regulatory_submission} (V165). Rule-2 tenant
 * isolation via unqualified name — never prefix with {@code public.} per
 * {@code bug_public_prefix_silent_rollback}.
 */
@Getter
@Setter
@NoArgsConstructor
@Table("regulatory_submission")
public class RegulatorySubmission {

    @Id
    private UUID id;

    @Column("tenant_id")
    private UUID tenantId;

    @Column("report_key")
    private String reportKey;

    @Column("period_start")
    private LocalDate periodStart;

    @Column("period_end")
    private LocalDate periodEnd;

    @Column("submission_number")
    private Integer submissionNumber;

    @Column("supersedes_id")
    private UUID supersedesId;

    @Column("source_run_id")
    private UUID sourceRunId;

    @Column("submitted_at")
    private OffsetDateTime submittedAt;

    @Column("submitted_by_actor_id")
    private UUID submittedByActorId;

    @Column("submitted_by_actor_email")
    private String submittedByActorEmail;

    @Column("xlsx_bytes")
    private byte[] xlsxBytes;

    @Column("xlsx_size_bytes")
    private Long xlsxSizeBytes;

    @Column("xlsx_content_hash")
    private String xlsxContentHash;

    @Column("filing_ref")
    private String filingRef;

    private String status;

    @Column("attestation_note")
    private String attestationNote;

    @Column("reason_note")
    private String reasonNote;

    @Column("created_at")
    private OffsetDateTime createdAt;
}

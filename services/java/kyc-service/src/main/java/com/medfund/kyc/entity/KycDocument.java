package com.medfund.kyc.entity;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A KYC document attached to a subject (Plan A Phase 2, tenant-family V278).
 * The byte payload lives in file-service; this row carries the handle
 * ({@code fileServiceRef} + {@code bucket}) plus the content hash for the
 * upload pipeline Plan B builds. Party-neutral like {@link KycVerification}:
 * keyed on {@code (subject_type, subject_id)}, no {@code tenant_id} column.
 */
@Getter
@Setter
@Table("kyc_documents")
public class KycDocument {

    @Id
    private UUID id;

    @Column("subject_type")
    private String subjectType;

    @Column("subject_id")
    private UUID subjectId;

    @Column("document_type")
    private String documentType;

    @Column("file_service_ref")
    private String fileServiceRef;

    private String bucket;

    @Column("content_type")
    private String contentType;

    @Column("size_bytes")
    private Long sizeBytes;

    private String sha256;

    private String status;

    @Column("uploaded_by")
    private UUID uploadedBy;

    @Column("uploaded_by_email")
    private String uploadedByEmail;

    @Column("uploaded_at")
    private Instant uploadedAt;
}

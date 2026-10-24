package com.medfund.kyc.entity;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Party-neutral KYC verification (Plan A Phase 2, tenant-family V278). One row
 * per subject — the identity is {@code (subject_type, subject_id)}, never the
 * insurance line. {@code insuranceLine} is contextual only: the line that
 * triggered the verification and feeds the VERIFICATION rules (Plan A Phase 3);
 * enhanced per-line requirements are expressed as a higher
 * {@code verificationLevel}, never as a second row (G6).
 *
 * <p>No {@code tenant_id} column: tenant isolation is the connection's
 * search_path (Critical Rule #2), set by the tenant-aware connection factory.
 */
@Getter
@Setter
@Table("kyc_verifications")
public class KycVerification {

    @Id
    private UUID id;

    @Column("subject_type")
    private String subjectType;

    @Column("subject_id")
    private UUID subjectId;

    /** Contextual only — the line that triggered this verification (G6). */
    @Column("insurance_line")
    private String insuranceLine;

    @Column("verification_status")
    private String verificationStatus;

    @Column("verification_level")
    private Short verificationLevel;

    private String method;

    @Column("ocr_prediction_id")
    private UUID ocrPredictionId;

    @Column("screening_prediction_id")
    private UUID screeningPredictionId;

    @Column("screening_result")
    private String screeningResult;

    @Column("verified_at")
    private Instant verifiedAt;

    @Column("verified_by")
    private UUID verifiedBy;

    @Column("verified_by_email")
    private String verifiedByEmail;

    @Column("rejected_reason")
    private String rejectedReason;

    @CreatedDate
    @Column("created_at")
    private Instant createdAt;

    @LastModifiedDate
    @Column("updated_at")
    private Instant updatedAt;

    @Column("updated_by")
    private UUID updatedBy;
}

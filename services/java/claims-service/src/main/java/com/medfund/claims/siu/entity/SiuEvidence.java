package com.medfund.claims.siu.entity;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Investigator-uploaded evidence linked to an {@code siu_case} — one row
 * per artifact (document, photo, provider record). File bytes live in
 * file-service; this row carries the {@code file_service_ref} handle so
 * the SIU workspace can render + download.
 *
 * <p>See {@code services/java/tenancy-service/.../db/migration/tenant/V172__siu_evidence.sql}
 * for schema. Uploaded via {@code POST /api/v1/siu/cases/{caseId}/evidence}
 * (gated on {@code claims:siu:investigate}).
 */
@Getter
@Setter
@Table("siu_evidence")
public class SiuEvidence {

    @Id
    private UUID id;

    @Column("case_id")           private UUID caseId;
    @Column("file_service_ref")  private String fileServiceRef;
    @Column("description")       private String description;
    @Column("evidence_type")     private String evidenceType;
    @Column("uploaded_by")       private UUID uploadedBy;
    @Column("uploaded_by_email") private String uploadedByEmail;
    // DB defaults to NOW() on INSERT — callers populate explicitly so the
    // returned entity carries the value (R2DBC doesn't re-fetch DEFAULT
    // columns; see [[bug_r2dbc_pre_populated_id_update_mode]] neighbour).
    @Column("uploaded_at")       private OffsetDateTime uploadedAt;
}

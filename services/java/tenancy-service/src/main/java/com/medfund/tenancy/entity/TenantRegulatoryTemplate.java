package com.medfund.tenancy.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@Table(schema = "public", value = "tenant_regulatory_template")
public class TenantRegulatoryTemplate {

    @Id
    private UUID id;

    @Column("tenant_id")
    private UUID tenantId;

    private String regulator;

    @Column("report_key")
    private String reportKey;

    @Column("version_label")
    private String versionLabel;

    @Column("effective_from")
    private LocalDate effectiveFrom;

    @Column("effective_to")
    private LocalDate effectiveTo;

    @Column("xlsx_bytes")
    private byte[] xlsxBytes;

    @Column("file_size_bytes")
    private Long fileSizeBytes;

    @Column("content_hash")
    private String contentHash;

    @Column("uploaded_at")
    private OffsetDateTime uploadedAt;

    @Column("actor_id")
    private UUID actorId;

    @Column("actor_email")
    private String actorEmail;

    private String notes;
}

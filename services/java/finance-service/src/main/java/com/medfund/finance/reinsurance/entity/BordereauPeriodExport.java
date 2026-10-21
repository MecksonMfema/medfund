package com.medfund.finance.reinsurance.entity;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Registry row per {@code (reinsurer, treaty, reportKey, year, quarter)}
 * (V089). First export inserts; subsequent re-exports increment
 * {@code exportCount}. Powers the {@code isPriorPeriodAdjustment} column
 * on the cession/recoveries bordereau — a cession whose {@code createdAt}
 * postdates {@code firstExportedAt} for the same quarter is flagged on
 * subsequent exports.
 */
@Getter
@Setter
@Table("bordereau_period_export")
public class BordereauPeriodExport {

    @Id
    private UUID id;

    @Column("reinsurer_id")
    private UUID reinsurerId;

    @Column("treaty_id")
    private UUID treatyId;

    @Column("report_key")
    private String reportKey;

    @Column("year")
    private Integer year;

    @Column("quarter")
    private Integer quarter;

    @Column("first_exported_at")
    private OffsetDateTime firstExportedAt;

    @Column("export_count")
    private Integer exportCount;

    @Column("actor_id")
    private UUID actorId;

    @Column("actor_email")
    private String actorEmail;
}

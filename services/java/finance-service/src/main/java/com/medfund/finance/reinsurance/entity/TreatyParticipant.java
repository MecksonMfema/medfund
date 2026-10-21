package com.medfund.finance.reinsurance.entity;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Treaty participant — one row per (treaty, reinsurer) pair (V084).
 * Composite key on {@code treatyId + reinsurerId}; no surrogate ID. The
 * repository hand-rolls SQL via {@code DatabaseClient} because R2DBC's
 * {@code ReactiveCrudRepository} does not support composite keys out of
 * the box.
 *
 * <p>{@code sharePct} across all participants of a given treaty MUST sum
 * to 100 for activation — enforced by
 * {@code TreatyValidationService}. {@code shareRole} distinguishes the
 * lead reinsurer from following participants for reporting purposes.
 */
@Getter
@Setter
@Table("treaty_participant")
public class TreatyParticipant {

    @Column("treaty_id")
    private UUID treatyId;

    @Column("reinsurer_id")
    private UUID reinsurerId;

    @Column("share_pct")
    private BigDecimal sharePct;

    @Column("share_role")
    private String shareRole;

    @Column("created_at")
    private OffsetDateTime createdAt;
}

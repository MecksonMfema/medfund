package com.medfund.finance.reinsurance.entity;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A single cession row (V087). Folds every cession source into one table:
 * {@code cessionType} discriminates LOSS (claim adjudicated) vs PREMIUM
 * (contribution paid or treaty inception premium), {@code source}
 * discriminates AUTOMATIC (consumer / scheduler) vs FACULTATIVE
 * (underwriter). Status subsets are enforced by CHECK constraints in V087.
 *
 * <p>{@code sourceEventId} + {@code cessionType} + {@code treatyId} are the
 * idempotency key: a re-delivered {@code medfund.claims.adjudicated} event
 * against the same treaty will bounce off the UNIQUE index
 * {@code ux_cession_source_event} rather than write a duplicate row.
 *
 * <p>Amount rules: {@code basisAmount} is the pre-cede value (e.g. the
 * claim's approvedAmount for LOSS, the contribution amount for PREMIUM);
 * {@code cededAmount} is the actual share to the treaty, computed by the
 * REINSURANCE rules-engine action. Both stored native — participant-share
 * splitting happens at bordereau-report time (R9).
 */
@Getter
@Setter
@Table("cession")
public class Cession {

    @Id
    private UUID id;

    @Column("treaty_id")
    private UUID treatyId;

    @Column("treaty_layer_id")
    private UUID treatyLayerId;

    @Column("cession_type")
    private String cessionType;

    @Column("source")
    private String source;

    @Column("status")
    private String status;

    @Column("source_event_id")
    private UUID sourceEventId;

    @Column("source_event_type")
    private String sourceEventType;

    @Column("ceded_amount")
    private BigDecimal cededAmount;

    @Column("currency_code")
    private String currencyCode;

    @Column("basis_amount")
    private BigDecimal basisAmount;

    @Column("occurred_at")
    private OffsetDateTime occurredAt;

    @Column("voided_reason")
    private String voidedReason;

    @Column("created_at")
    private OffsetDateTime createdAt;

    @Column("updated_at")
    private OffsetDateTime updatedAt;

    @Column("actor_id")
    private UUID actorId;

    @Column("actor_email")
    private String actorEmail;
}

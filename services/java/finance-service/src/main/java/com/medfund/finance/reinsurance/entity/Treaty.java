package com.medfund.finance.reinsurance.entity;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Reinsurance treaty (V082). A single contract with one or more reinsurers
 * ({@link TreatyParticipant}) that shares underlying risk on a slice of the
 * tenant's book. {@code treatyType} discriminator selects proportional
 * (QUOTA_SHARE / SURPLUS_SHARE) vs non-proportional (EXCESS_OF_LOSS /
 * STOP_LOSS) shapes; layers only apply to non-proportional treaties.
 *
 * <p>{@code renewedFromTreatyId} lets the UI walk the renewal chain. A
 * treaty's status is DRAFT while authoring, ACTIVE from
 * {@code activatedAt} to {@code expiryDate}, and terminal after that
 * (EXPIRED / RENEWED / LAPSED / COMMUTED).
 */
@Getter
@Setter
@Table("treaty")
public class Treaty {

    @Id
    private UUID id;

    @Column("treaty_ref")
    private String treatyRef;

    @Column("treaty_type")
    private String treatyType;

    @Column("declared_currency")
    private String declaredCurrency;

    @Column("inception_date")
    private LocalDate inceptionDate;

    @Column("expiry_date")
    private LocalDate expiryDate;

    @Column("status")
    private String status;

    @Column("renewed_from_treaty_id")
    private UUID renewedFromTreatyId;

    @Column("aggregate_limit")
    private BigDecimal aggregateLimit;

    @Column("aggregate_limit_currency")
    private String aggregateLimitCurrency;

    @Column("expected_annual_premium")
    private BigDecimal expectedAnnualPremium;

    @Column("producer_ref")
    private String producerRef;

    @Column("activated_at")
    private OffsetDateTime activatedAt;

    @Column("created_at")
    private OffsetDateTime createdAt;

    @Column("updated_at")
    private OffsetDateTime updatedAt;

    @Column("actor_id")
    private UUID actorId;

    @Column("actor_email")
    private String actorEmail;
}

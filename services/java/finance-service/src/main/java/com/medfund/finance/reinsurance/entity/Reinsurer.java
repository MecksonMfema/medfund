package com.medfund.finance.reinsurance.entity;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Reinsurer counterparty master (V081). One row per external counterparty
 * a tenant places treaties with. {@code isActive} + the partial unique
 * index on {@code name} allow a rebrand: deactivate the old row, create
 * a new active row with the same display name.
 */
@Getter
@Setter
@Table("reinsurer")
public class Reinsurer {

    @Id
    private UUID id;

    @Column("name")
    private String name;

    @Column("contact_email")
    private String contactEmail;

    @Column("contact_address")
    private String contactAddress;

    @Column("jurisdiction_code")
    private String jurisdictionCode;

    @Column("home_currency")
    private String homeCurrency;

    @Column("credit_rating")
    private String creditRating;

    @Column("is_active")
    private Boolean active = true;

    @Column("created_at")
    private OffsetDateTime createdAt;

    @Column("updated_at")
    private OffsetDateTime updatedAt;

    @Column("actor_id")
    private UUID actorId;

    @Column("actor_email")
    private String actorEmail;
}

package com.medfund.user.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@Table(schema = "public", value = "providers")
public class Provider {

    @Id
    private UUID id;

    private String name;

    @Column("provider_type")
    private String providerType;

    /**
     * Generic registration / licence / AHFOZ number.
     * What this represents depends on the tenant's insurance line — e.g.
     * AHFOZ number for health-insurance tenants, workshop licence for motor.
     * The tenant's {@code settings.providerRegLabel} controls the UI label.
     */
    @Column("registration_number")
    private String registrationNumber;

    private String specialty;

    private String email;

    private String phone;

    private String city;

    private String address;

    @Column("banking_details")
    private String bankingDetails;

    @Column("keycloak_user_id")
    private String keycloakUserId;

    private String status;

    /**
     * Phase 13 §A per L1. Grouping dimension for
     * {@code PROVIDER_NETWORK_UTILIZATION}. Vocab: STANDARD, TIER_1,
     * TIER_2, TIER_3. Defaults to STANDARD on the DB side; the admin
     * inline dropdown is the only writer today.
     */
    @Column("network_tier")
    private String networkTier;

    @CreatedDate
    @Column("created_at")
    private Instant createdAt;

    @LastModifiedDate
    @Column("updated_at")
    private Instant updatedAt;

    @Column("created_by")
    private UUID createdBy;

    @Column("updated_by")
    private UUID updatedBy;
}

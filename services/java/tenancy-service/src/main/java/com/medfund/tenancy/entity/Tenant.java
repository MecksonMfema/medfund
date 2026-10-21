package com.medfund.tenancy.entity;

import com.medfund.tenancy.util.JsonString;
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
@Table(schema = "public", value = "tenants")
public class Tenant {

    @Id
    private UUID id;

    private String name;
    private String slug;
    private String domain;

    @Column("schema_name")
    private String schemaName;

    @Column("plan_id")
    private UUID planId;

    private String status;

    /** Stored as PostgreSQL {@code jsonb}; R2dbcConfig converters handle the mapping. */
    private JsonString settings;

    /** Stored as PostgreSQL {@code jsonb}; R2dbcConfig converters handle the mapping. */
    private JsonString branding;

    @Column("contact_email")
    private String contactEmail;

    @Column("country_code")
    private String countryCode;

    private String timezone;

    @Column("membership_model")
    private String membershipModel;

    /** Pricing source: AGE_GROUP (default — age_groups.contribution_amount)
     *  or INDIVIDUAL (per-member billing_override_amount when set, else
     *  age-group fallback). Set per V118. */
    @Column("pricing_model")
    private String pricingModel;

    /** Member-number issuance scheme (V120). INDEPENDENT (default) gives
     *  members "MBR-XXXXXX" and dependants "DEP-XXXXXX"; SHARED_WITH_SUFFIX
     *  uses a shared base + monotonically increasing "-NN" suffix
     *  ("MBR-XXXXXX-01" for the member, "-02" for the first dependant). */
    @Column("member_number_scheme")
    private String memberNumberScheme;

    @Column("keycloak_realm")
    private String keycloakRealm;

    /**
     * Regulator jurisdiction (V131). Free-form string persisted from a
     * fixed enum in the tenant-admin form — see the financial-reporting
     * suite. NULL = no regulator-templated reports are surfaced for this
     * tenant.
     */
    @Column("jurisdiction_code")
    private String jurisdictionCode;

    @CreatedDate
    @Column("created_at")
    private Instant createdAt;

    @LastModifiedDate
    @Column("updated_at")
    private Instant updatedAt;
}

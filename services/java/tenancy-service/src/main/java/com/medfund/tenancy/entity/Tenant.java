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

    @Column("keycloak_realm")
    private String keycloakRealm;

    @CreatedDate
    @Column("created_at")
    private Instant createdAt;

    @LastModifiedDate
    @Column("updated_at")
    private Instant updatedAt;
}

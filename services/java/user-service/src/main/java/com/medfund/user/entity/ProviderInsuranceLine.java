package com.medfund.user.entity;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * (provider, insurance line) tag (V185).
 *
 * <p>Composite key on {@code providerId + insuranceLine}; the repository
 * hand-rolls SQL for the same reason as {@link ProviderTenant}.
 * {@code insuranceLine} is CHECK-constrained in the schema to the
 * {@code InsuranceLine} enum values in {@code services/java/shared}.
 */
@Getter
@Setter
@Table(schema = "public", value = "provider_insurance_lines")
public class ProviderInsuranceLine {

    @Column("provider_id")
    private UUID providerId;

    @Column("insurance_line")
    private String insuranceLine;

    @Column("created_at")
    private OffsetDateTime createdAt;
}

package com.medfund.tenancy.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@Table(schema = "public", value = "tenant_currency_config")
public class TenantCurrencyConfig {

    @Id
    private UUID id;

    @Column("tenant_id")
    private UUID tenantId;

    @Column("currency_code")
    private String currencyCode;

    @Column("is_default")
    private Boolean isDefault;

    @Column("is_active")
    private Boolean isActive;
}

package com.medfund.tenancy.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@Table(schema = "public", value = "exchange_rates")
public class ExchangeRate {

    @Id
    private UUID id;

    @Column("base_currency")
    private String baseCurrency;

    @Column("quote_currency")
    private String quoteCurrency;

    private BigDecimal rate;

    @Column("rate_date")
    private LocalDate rateDate;

    private String source;

    @Column("tenant_id")
    private UUID tenantId;

    @Column("created_at")
    private Instant createdAt;

    @Column("created_by")
    private UUID createdBy;
}

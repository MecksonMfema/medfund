package com.medfund.tenancy.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@Table(schema = "public", value = "currencies")
public class Currency {

    @Id
    private String code;

    private String name;

    private String symbol;

    @Column("decimal_places")
    private Short decimalPlaces;

    @Column("is_active")
    private Boolean isActive;

    @Column("created_at")
    private Instant createdAt;
}

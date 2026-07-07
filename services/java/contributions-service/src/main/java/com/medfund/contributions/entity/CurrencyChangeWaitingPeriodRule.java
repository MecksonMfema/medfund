package com.medfund.contributions.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Direction-specific waiting rule for cross-currency scheme changes
 * (V049). Consulted by {@link
 * com.medfund.contributions.service.SchemeChangeService#resolveEffectiveDate}
 * when {@code change_kind = CURRENCY_CHANGE}. Missing row → the
 * change effects on the requester's supplied date (no wait).
 */
@Getter
@Setter
@NoArgsConstructor
@Table("currency_change_waiting_period_rules")
public class CurrencyChangeWaitingPeriodRule {

    @Id
    private UUID id;

    @Column("from_currency")
    private String fromCurrency;

    @Column("to_currency")
    private String toCurrency;

    @Column("waiting_days")
    private Integer waitingDays;

    private String description;

    @Column("is_active")
    private Boolean isActive;

    @Column("created_at")
    private Instant createdAt;

    @Column("updated_at")
    private Instant updatedAt;
}

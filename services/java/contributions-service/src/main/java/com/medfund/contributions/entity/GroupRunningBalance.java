package com.medfund.contributions.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@Table("group_running_balance")
public class GroupRunningBalance {

    @Id
    private UUID id;

    @Column("group_id")
    private UUID groupId;

    @Column("currency_code")
    private String currencyCode;

    private BigDecimal balance;

    @Column("last_charge_at")
    private Instant lastChargeAt;

    @Column("last_payment_at")
    private Instant lastPaymentAt;

    @Column("updated_at")
    private Instant updatedAt;
}

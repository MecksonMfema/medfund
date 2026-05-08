package com.medfund.contributions.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@Table("transaction_types")
public class TransactionType {

    @Id
    private UUID id;

    private String code;
    private String label;
    private String description;

    /** '+' for inflow / debit-the-balance, '-' for outflow / credit-the-balance. */
    private String sign;

    @Column("requires_approval")
    private Boolean requiresApproval;

    @Column("is_active")
    private Boolean isActive;

    @Column("created_at")
    private Instant createdAt;

    @Column("updated_at")
    private Instant updatedAt;
}

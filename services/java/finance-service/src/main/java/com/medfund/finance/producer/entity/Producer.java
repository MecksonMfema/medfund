package com.medfund.finance.producer.entity;

import io.r2dbc.postgresql.codec.Json;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Producer / broker registry entry (V092). One row per external counterparty
 * that sources members for the tenant. Supports a self-referential hierarchy
 * via {@code parentProducerId}; the plain FK is rewritten on reparent — audit
 * trail is preserved via {@code AuditEvent} rather than a time-slice table.
 *
 * {@code homeCurrency} pins the producer's payout currency and locks FX at
 * payout-run commit time per {@code .claude/multi-currency.md:164}.
 */
@Getter
@Setter
@Table("producer")
public class Producer {

    @Id
    private UUID id;

    @Column("producer_code")
    private String producerCode;

    @Column("name")
    private String name;

    @Column("contact_email")
    private String contactEmail;

    @Column("contact_phone")
    private String contactPhone;

    @Column("jurisdiction_code")
    private String jurisdictionCode;

    @Column("home_currency")
    private String homeCurrency;

    @Column("parent_producer_id")
    private UUID parentProducerId;

    @Column("wht_pct_override")
    private BigDecimal whtPctOverride;

    @Column("banking_details")
    private Json bankingDetails;

    public String getBankingDetailsJson() {
        return bankingDetails == null ? null : bankingDetails.asString();
    }

    public void setBankingDetailsJson(String bankingDetailsJson) {
        this.bankingDetails = bankingDetailsJson == null ? null : Json.of(bankingDetailsJson);
    }

    @Column("is_active")
    private Boolean active = true;

    @Column("activated_at")
    private OffsetDateTime activatedAt;

    @Column("terminated_at")
    private OffsetDateTime terminatedAt;

    @Column("created_at")
    private OffsetDateTime createdAt;

    @Column("updated_at")
    private OffsetDateTime updatedAt;

    @Column("actor_id")
    private UUID actorId;

    @Column("actor_email")
    private String actorEmail;
}

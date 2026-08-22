package com.medfund.finance.reinsurance.entity;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The tenant's recovery position against a single cession (V088).
 * Lifecycle:
 *
 * <ul>
 *   <li><b>EXPECTED</b> — written by {@link com.medfund.finance.reinsurance.service.CessionService}
 *       at cession-time (Phase 3 deviation). The plan originally sourced this
 *       from a {@code payment-created} consumer, but that topic carries no
 *       claim linkage and Payment/PaymentRunItem are provider-aggregate;
 *       see plan Deviations.</li>
 *   <li><b>INVOICED</b> — flipped by the recoveries bordereau export
 *       (Phase 4).</li>
 *   <li><b>RECEIVED</b> — recorded via the manual form (Phase 8).</li>
 *   <li><b>WRITTEN_OFF</b> — recorded via the manual write-off form
 *       (Phase 8).</li>
 * </ul>
 *
 * <p>Idempotency: the UNIQUE (cession_id) index on this table (V088) means
 * a rerun of the loss cession consumer with the same claim will not
 * write a duplicate recovery — the {@code CessionService} treats the
 * unique violation as a no-op the same way it does for cessions.
 */
@Getter
@Setter
@Table("recovery")
public class Recovery {

    @Id
    private UUID id;

    @Column("cession_id")
    private UUID cessionId;

    @Column("status")
    private String status;

    @Column("expected_amount")
    private BigDecimal expectedAmount;

    @Column("received_amount")
    private BigDecimal receivedAmount;

    @Column("currency_code")
    private String currencyCode;

    @Column("invoiced_at")
    private OffsetDateTime invoicedAt;

    @Column("received_at")
    private OffsetDateTime receivedAt;

    @Column("write_off_reason")
    private String writeOffReason;

    @Column("created_at")
    private OffsetDateTime createdAt;

    @Column("updated_at")
    private OffsetDateTime updatedAt;

    @Column("actor_id")
    private UUID actorId;

    @Column("actor_email")
    private String actorEmail;
}

package com.medfund.finance.producer.entity;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Clawback trigger row (V096). One row per commission reversal event.
 * {@code source} folds two triggers into one register:
 * <ul>
 *   <li>{@code MEMBER_LAPSE} — the paying member lapsed / terminated /
 *       deactivated within the {@code commission_rate_card.clawback_window_days}
 *       window measured from the accrual date.</li>
 *   <li>{@code CONTRIBUTION_REVOKE} — the underlying contribution was
 *       revoked from the billing ledger.</li>
 * </ul>
 *
 * <p>Idempotency via {@code ux_clawback_by_source}: {@code
 * (source, triggering_event_ref, commission_transaction_id)} — a replayed
 * lifecycle or revoke event never writes a duplicate clawback.
 */
@Getter
@Setter
@Table("clawback_event")
public class ClawbackEvent {

    @Id
    private UUID id;

    @Column("source")
    private String source;

    @Column("triggering_event_ref")
    private String triggeringEventRef;

    @Column("member_id")
    private UUID memberId;

    @Column("producer_id")
    private UUID producerId;

    @Column("commission_transaction_id")
    private UUID commissionTransactionId;

    @Column("reversal_txn_id")
    private UUID reversalTxnId;

    @Column("native_amount")
    private BigDecimal nativeAmount;

    @Column("native_currency")
    private String nativeCurrency;

    @Column("reason")
    private String reason;

    @Column("occurred_at")
    private OffsetDateTime occurredAt;

    @Column("created_at")
    private OffsetDateTime createdAt;

    @Column("actor_id")
    private UUID actorId;

    @Column("actor_email")
    private String actorEmail;
}

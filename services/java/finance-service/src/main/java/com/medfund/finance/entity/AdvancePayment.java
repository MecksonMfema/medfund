package com.medfund.finance.entity;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Table("advance_payments")
public class AdvancePayment {

    @Id
    private UUID id;

    // Deprecated — advance-payment consumption is now tracked in the
    // advance_payment_applications bridging table (V068). Column retained
    // for historical rows and legacy readers; new write paths must not
    // populate it.
    @Deprecated
    @Column("payment_id")
    private UUID paymentId;

    @Column("provider_id")
    private UUID providerId;

    @Column("member_id")
    private UUID memberId;

    private BigDecimal amount;

    @Column("currency_code")
    private String currencyCode;

    @Column("payment_method")
    private String paymentMethod;

    private String reference;

    private String comment;

    @CreatedDate
    @Column("recorded_at")
    private Instant recordedAt;

    @Column("recorded_by")
    private UUID recordedBy;

    /** 'ADVANCE' (original) or 'REVERSAL' (compensating entry). */
    private String type;

    /** 'pending' | 'approved' | 'applied' | 'reversed'. */
    private String status;

    @Column("approved_by")
    private UUID approvedBy;

    @Column("approved_at")
    private Instant approvedAt;

    /** Only populated on REVERSAL rows — points back to the ADVANCE they negate. */
    @Column("reverses_advance_id")
    private UUID reversesAdvanceId;
}

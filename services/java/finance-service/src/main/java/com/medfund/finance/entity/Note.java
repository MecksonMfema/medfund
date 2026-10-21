package com.medfund.finance.entity;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Finance-side note ledger row. Replaces the V016 {@code Adjustment}
 * entity and the memo-only {@code DebitNote}/{@code CreditNote} tables
 * (V074 folds them all in). The {@code direction} axis says which way
 * the money moves; {@code note_type} picks the reason (TAX_WITHHELD,
 * WRITE_OFF, GOODWILL, ENDORSEMENT_PREMIUM, PREMIUM_REFUND,
 * PROVIDER_OVERPAYMENT_RECOVERY, MEMO). {@code type='REVERSAL'} +
 * {@code reversesNoteId} is the compensating-entry pattern shared with
 * {@code AdvancePayment} and {@code CtcPayment}.
 */
@Getter
@Setter
@Table("notes")
public class Note {

    @Id
    private UUID id;

    @Column("note_number")
    private String noteNumber;

    @Column("provider_id")
    private UUID providerId;

    @Column("member_id")
    private UUID memberId;

    /** {@code DEBIT} = payee owes us; {@code CREDIT} = we owe payee. */
    private String direction;

    @Column("note_type")
    private String noteType;

    /** {@code ORIGINAL} or {@code REVERSAL}. */
    private String type = "ORIGINAL";

    @Column("reverses_note_id")
    private UUID reversesNoteId;

    private BigDecimal amount;

    @Column("currency_code")
    private String currencyCode;

    private String reason;

    /** {@code pending} → {@code approved} → {@code applied} → {@code reversed}. */
    private String status = "pending";

    @Column("approved_by")
    private UUID approvedBy;

    @Column("approved_at")
    private Instant approvedAt;

    @Column("posted_at")
    private Instant postedAt;

    @CreatedDate
    @Column("created_at")
    private Instant createdAt;

    @LastModifiedDate
    @Column("updated_at")
    private Instant updatedAt;

    @Column("created_by")
    private UUID createdBy;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Note other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}

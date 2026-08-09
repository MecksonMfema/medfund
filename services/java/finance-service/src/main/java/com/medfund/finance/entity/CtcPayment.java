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
@Table("ctc_payments")
public class CtcPayment {

    @Id
    private UUID id;

    @Column("group_id")
    private UUID groupId;

    @Column("member_id")
    private UUID memberId;

    private BigDecimal amount;

    @Column("currency_code")
    private String currencyCode;

    @Column("contribution_id")
    private UUID contributionId;

    /**
     * Legacy back-compat flag. Kept in sync with {@link #status} for one
     * release so external readers that still key off {@code committed}
     * don't break; removed in a follow-up cleanup migration. New code
     * reads {@link #status} directly.
     */
    private Boolean committed = false;

    @CreatedDate
    @Column("created_at")
    private Instant createdAt;

    @Column("created_by")
    private UUID createdBy;

    /** 'CTC' (original) or 'REVERSAL' (compensating entry). Default 'CTC'. */
    private String type;

    /** 'draft' | 'committed' | 'reversed'. Default 'draft'. */
    private String status;

    /**
     * The member-payable this CTC offsets. Required on new CTC rows;
     * NULL on historical pre-V069 rows and on REVERSAL rows (which
     * carry the original's payable via {@link #reversesCtcId}).
     */
    @Column("member_payable_id")
    private UUID memberPayableId;

    /** Only populated on REVERSAL rows — points at the CTC they negate. */
    @Column("reverses_ctc_id")
    private UUID reversesCtcId;

    @Column("committed_at")
    private Instant committedAt;

    @Column("committed_by")
    private UUID committedBy;
}

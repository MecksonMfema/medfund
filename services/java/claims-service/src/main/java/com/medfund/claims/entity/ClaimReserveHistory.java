package com.medfund.claims.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Point-in-time case-reserve entry per claim (actuarial Phase 14 §A). Rows are
 * append-only in intent — history is the reserve as of {@link #effectiveAt}.
 * Populated both by adjudicator action (ClaimReserveController.set) and by
 * auto-zero on claim REJECTED / CANCELLED transitions.
 */
@Getter
@Setter
@NoArgsConstructor
@Table("claim_reserve_history")
public class ClaimReserveHistory {

    @Id
    private UUID id;

    @Column("claim_id")
    private UUID claimId;

    @Column("reserved_amount")
    private BigDecimal reservedAmount;

    @Column("effective_at")
    private OffsetDateTime effectiveAt;

    @Column("actor_id")
    private UUID actorId;

    @Column("actor_email")
    private String actorEmail;

    @Column("reason_note")
    private String reasonNote;

    @Column("created_at")
    private OffsetDateTime createdAt;
}

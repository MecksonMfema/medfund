package com.medfund.claims.siu.entity;

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
 * Audit-of-record row for a single fraud prediction — one row per classified
 * claim (LOW/MEDIUM/HIGH) per Rule 3 (AI decisions must be auditable). Rows
 * are immutable in intent: no application code UPDATEs a row after insert,
 * apart from the {@code siu_case_id} back-link written when FRAUD_TRIAGE
 * promotes the flag to an investigation.
 *
 * <p>Populated by Phase 4's {@code FraudFlaggedConsumer} from the
 * {@code medfund.claims.fraud-flagged} topic emitted by the AI service
 * (Phase 3). Manual officer-opened flags carry {@code flag_source =
 * MANUAL_OFFICER} and leave the AI-only fields null (CHECK constraint
 * enforces the split).
 */
@Getter
@Setter
@Table("fraud_flag")
public class FraudFlag {

    @Id
    private UUID id;

    @Column("claim_id")       private UUID claimId;
    @Column("siu_case_id")    private UUID siuCaseId;
    @Column("flag_source")    private String flagSource;      // AI_MODEL | MANUAL_OFFICER
    @Column("model_version")  private String modelVersion;
    @Column("risk_score")     private BigDecimal riskScore;
    @Column("risk_level")     private String riskLevel;
    @Column("indicators")     private Json indicators;        // JSONB
    @Column("flagged_at")     private OffsetDateTime flaggedAt;
    @Column("correlation_id") private String correlationId;
    @Column("created_at")     private OffsetDateTime createdAt;
}

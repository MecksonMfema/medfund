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
 * Staging row for the legacy {@code treaty.producer_ref} → {@code producer.id}
 * backfill (V098). One row per plausible fuzzy match; low-confidence rows queue
 * for tenant-admin review, high-confidence rows are marked ACCEPTED immediately
 * by {@link com.medfund.finance.producer.service.ProducerBackfillJob}.
 */
@Getter
@Setter
@Table("producer_backfill_candidate")
public class ProducerBackfillCandidate {

    @Id
    private UUID id;

    @Column("treaty_id")
    private UUID treatyId;

    @Column("treaty_producer_ref")
    private String treatyProducerRef;

    @Column("candidate_producer_id")
    private UUID candidateProducerId;

    @Column("confidence_score")
    private BigDecimal confidenceScore;

    @Column("match_strategy")
    private String matchStrategy;

    @Column("status")
    private String status;

    @Column("resolved_at")
    private OffsetDateTime resolvedAt;

    @Column("resolved_actor_id")
    private UUID resolvedActorId;

    @Column("resolved_actor_email")
    private String resolvedActorEmail;

    @Column("created_at")
    private OffsetDateTime createdAt;
}

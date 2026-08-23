package com.medfund.finance.producer.entity;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Time-slice assignment of a member to a producer (V093). At most one open
 * ({@code effective_to IS NULL}) row per member — enforced by the partial
 * UNIQUE index {@code ux_mpa_one_open_per_member} at V093:22 and by the
 * app-layer guard in {@link com.medfund.finance.producer.service.MemberProducerAssignmentService}.
 * Reassignment closes the prior open row and inserts a new one in the same
 * transaction.
 */
@Getter
@Setter
@Table("member_producer_assignment")
public class MemberProducerAssignment {

    @Id
    private UUID id;

    @Column("member_id")
    private UUID memberId;

    @Column("producer_id")
    private UUID producerId;

    @Column("effective_from")
    private LocalDate effectiveFrom;

    @Column("effective_to")
    private LocalDate effectiveTo;

    @Column("change_reason")
    private String changeReason;

    @Column("created_at")
    private OffsetDateTime createdAt;

    @Column("actor_id")
    private UUID actorId;

    @Column("actor_email")
    private String actorEmail;
}

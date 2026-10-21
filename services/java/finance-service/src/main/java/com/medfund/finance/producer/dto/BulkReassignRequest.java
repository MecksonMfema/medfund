package com.medfund.finance.producer.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Payload for a bulk-reassign operation post producer termination. The
 * {@code effectiveFrom} snaps to 1st-of-month per {@code feedback_effective_date_snap}.
 * Each member row runs its own transaction via
 * {@link com.medfund.finance.producer.service.MemberProducerAssignmentService#assign}
 * — one failure does not fail the batch.
 */
public record BulkReassignRequest(
        @NotNull UUID newProducerId,
        @NotEmpty List<UUID> memberIds,
        @NotNull LocalDate effectiveFrom,
        @Size(max = 120) String changeReason
) {}

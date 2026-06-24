package com.medfund.contributions.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Response from the enqueue endpoint. {@code configId} + {@code runId} are
 * the two handles the UI needs to short-poll
 * {@code GET /api/v1/scheduled-jobs/{configId}/runs}.
 */
public record EnqueueBillingResponse(
        UUID configId,
        UUID runId,
        String status,
        Instant startedAt
) {}

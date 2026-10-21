package com.medfund.finance.actuarial.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Poll response for {@code GET /api/v1/reports/actuarial/jobs/{jobId}}.
 * {@code progressPct} is a coarse three-step estimate for the Angular
 * spinner — {@code requested=10}, {@code processing=50},
 * {@code completed|failed=100}. Not tied to compute internals.
 */
public record JobStatusResponse(
        UUID jobId,
        String reportKey,
        String status,
        int progressPct,
        JsonNode paramsJson,
        JsonNode resultJson,
        String errorMessage,
        OffsetDateTime requestedAt,
        OffsetDateTime completedAt) {}

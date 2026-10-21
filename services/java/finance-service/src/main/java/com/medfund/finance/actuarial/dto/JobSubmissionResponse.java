package com.medfund.finance.actuarial.dto;

import java.util.UUID;

/**
 * Inline response for {@code POST /api/v1/reports/actuarial/*} — the caller
 * receives {@link #jobId} immediately and polls
 * {@code GET /api/v1/reports/actuarial/jobs/{jobId}} for the terminal state.
 *
 * <p>{@link #deduplicated} is {@code true} when an in-flight identical job
 * was found and reused — the frontend uses this to skip its "submitting…"
 * toast and go straight to polling.
 */
public record JobSubmissionResponse(UUID jobId, String status, boolean deduplicated) {}

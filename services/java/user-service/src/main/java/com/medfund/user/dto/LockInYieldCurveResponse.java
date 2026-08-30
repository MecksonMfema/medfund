package com.medfund.user.dto;

import java.util.UUID;

/**
 * Response for the yield-curve lock-in endpoint. {@code firstPolicy=true}
 * means this call actually wrote the snapshot; {@code false} means the
 * cohort was already locked (either by a prior policy or the V159 backfill).
 * Callers can log the two cases differently but neither is an error.
 */
public record LockInYieldCurveResponse(UUID cohortId, boolean firstPolicy) {}

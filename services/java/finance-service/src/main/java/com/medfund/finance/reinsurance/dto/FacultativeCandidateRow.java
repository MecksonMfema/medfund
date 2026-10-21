package com.medfund.finance.reinsurance.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Row shape for the facultative-candidates browse. One row per adjudicated
 * claim above the caller-supplied threshold that has not yet been ceded to
 * *any* treaty (auto or facultative) — the underwriter picks a candidate,
 * chooses a treaty, and drops a DRAFT facultative cession against it.
 *
 * <p>Fields mirror {@code claims-service.ClaimRow} for the display columns
 * plus a boolean {@code alreadyCeded} so the client can grey out rows for
 * which an auto-cession slipped in between load and cede.
 */
public record FacultativeCandidateRow(
        UUID claimId,
        String claimNumber,
        UUID memberId,
        String memberName,
        UUID providerId,
        String providerName,
        String insuranceLine,
        BigDecimal approvedAmount,
        String currencyCode,
        Instant submissionDate,
        boolean alreadyCeded
) {}

package com.medfund.contributions.dto;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Result of a successful revoke. {@code contributionsDeleted} and
 * {@code invoicesDeleted} let the wizard show a confirmation toast
 * with the exact counts so the operator can sanity-check before
 * re-committing.
 */
public record BillingRevokeResponse(
        long contributionsDeleted,
        long invoicesDeleted,
        LocalDate periodStart,
        LocalDate periodEnd,
        String insuranceLine,
        Instant revokedAt
) {}

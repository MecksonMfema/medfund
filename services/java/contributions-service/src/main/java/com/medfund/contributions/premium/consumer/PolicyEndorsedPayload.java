package com.medfund.contributions.premium.consumer;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Deserialised shape of the {@code medfund.user.policy-endorsed} payload
 * (Phase 12 §C Phase 8). Wire format matches
 * {@code user-service.PolicyEndorsedPublisher} field-for-field — string
 * fields on the wire, empty strings mapped to null on the consumer side.
 * {@code premiumDelta} is signed; {@code effectiveFrom} snaps to
 * 1st-of-month upstream.
 */
public record PolicyEndorsedPayload(
        String tenantId,
        UUID endorsementId,
        String reference,
        UUID policyId,
        String policySource,
        String insuranceLine,
        String changeType,
        LocalDate effectiveFrom,
        BigDecimal premiumDelta,
        String currencyCode,
        Instant committedAt
) {
}

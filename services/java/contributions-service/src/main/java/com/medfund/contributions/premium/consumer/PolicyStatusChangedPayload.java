package com.medfund.contributions.premium.consumer;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Deserialised shape of the {@code medfund.user.policy-status-changed}
 * payload (Phase 13 §B per L6). Wire format matches
 * {@code user-service.PolicyStatusChangedPublisher} field-for-field —
 * every field is a JSON string on the wire, empty strings map to null
 * on the consumer side.
 *
 * <p>{@code toStatus} + {@code fromStatus} come across as the lowercase
 * status vocab used across the policy state machine
 * ({@code active}, {@code lapsed}, {@code suspended}, {@code terminated}).
 * {@code effectiveAt} preserves the transaction commit timestamp exactly
 * — no 1st-of-month snap per {@code feedback_effective_date_snap}.
 */
public record PolicyStatusChangedPayload(
        String tenantId,
        UUID policyId,
        String policySource,
        String insuranceLine,
        String fromStatus,
        String toStatus,
        OffsetDateTime effectiveAt,
        String reasonCode,
        String actorId,
        String actorEmail
) {
}

package com.medfund.contributions.premium.consumer;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Deserialised shape of the {@code medfund.user.policy-issued} payload
 * produced by user-service {@code PolicyIssuedPublisher}. Field names +
 * types kept in lockstep with that publisher — every value on the wire is
 * a JSON string (empty strings map to null on the consumer side).
 */
public record PolicyIssuedPayload(
        String tenantId,
        UUID policyId,
        String policyNumber,
        String policySource,
        String insuranceLine,
        BigDecimal writtenPremium,
        String currencyCode,
        LocalDate coverageStart,
        LocalDate coverageEnd,
        Instant boundAt,
        UUID memberId,
        UUID portfolioId,
        UUID cohortId,
        UUID renewedFromPolicyId
) {
}

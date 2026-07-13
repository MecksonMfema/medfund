package com.medfund.finance.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Row shape returned by {@code GET /ctc-payments/page}. Carries the
 * joined member/group display fields so the CTC list renders each cell
 * without a second lookup. The previous unpaginated flow surfaced raw
 * UUIDs to the operator, which was hostile at scale.
 */
public record CtcPaymentRow(
        UUID id,
        UUID groupId,
        String groupName,
        UUID memberId,
        String memberName,
        String memberNumber,
        BigDecimal amount,
        String currencyCode,
        UUID contributionId,
        Boolean committed,
        Instant createdAt,
        UUID createdBy
) {
}

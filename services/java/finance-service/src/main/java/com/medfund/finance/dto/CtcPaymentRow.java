package com.medfund.finance.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Row shape returned by {@code GET /ctc-payments/page}. Carries the
 * joined member/group display fields so the CTC list renders each cell
 * without a second lookup. Also carries the V069 lifecycle columns
 * ({@code type}, {@code status}, {@code memberPayableId}) so the list
 * can render status pills and the reverse action can be gated per-row.
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
        UUID createdBy,
        String type,
        String status,
        UUID memberPayableId
) {
}

package com.medfund.finance.regulatory.aml.dto;

import com.medfund.finance.regulatory.aml.entity.SuspiciousTransactionAlert;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Read-side view of a {@link SuspiciousTransactionAlert}. Compact projection
 * suitable for the queue list + detail page — actor emails are surfaced so
 * the UI can show "Raised by X, reviewed by Y" without a second call.
 */
public record AmlAlertResponse(
        UUID id,
        String status,
        String transactionRef,
        String transactionType,
        BigDecimal amountNative,
        String currency,
        UUID memberId,
        UUID providerId,
        String description,
        UUID raisedByActorId,
        String raisedByActorEmail,
        OffsetDateTime raisedAt,
        UUID reviewerActorId,
        String reviewerActorEmail,
        OffsetDateTime reviewedAt,
        String reviewNote,
        UUID filerActorId,
        String filerActorEmail,
        OffsetDateTime filedAt,
        String filedRef,
        String filedXlsxRef,
        UUID closerActorId,
        String closerActorEmail,
        OffsetDateTime closedAt,
        String closedReason
) {

    public static AmlAlertResponse from(SuspiciousTransactionAlert row) {
        return new AmlAlertResponse(
                row.getId(),
                row.getStatus(),
                row.getTransactionRef(),
                row.getTransactionType(),
                row.getAmountNative(),
                row.getCurrency(),
                row.getMemberId(),
                row.getProviderId(),
                row.getDescription(),
                row.getRaisedByActorId(),
                row.getRaisedByActorEmail(),
                row.getRaisedAt(),
                row.getReviewerActorId(),
                row.getReviewerActorEmail(),
                row.getReviewedAt(),
                row.getReviewNote(),
                row.getFilerActorId(),
                row.getFilerActorEmail(),
                row.getFiledAt(),
                row.getFiledRef(),
                row.getFiledXlsxRef(),
                row.getCloserActorId(),
                row.getCloserActorEmail(),
                row.getClosedAt(),
                row.getClosedReason()
        );
    }
}

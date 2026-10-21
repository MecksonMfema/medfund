package com.medfund.finance.reinsurance.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One recovery-per-participant row on the recoveries bordereau report.
 * Same participant-split shape as {@link CessionBordereauRow}: the
 * participant's expected share is {@code cession.cededAmount × sharePct / 100}
 * and the actual receipt (if RECEIVED) is {@code recovery.receivedAmount ×
 * sharePct / 100}.
 */
public record RecoveriesBordereauRow(
        UUID recoveryId,
        UUID cessionId,
        UUID treatyId,
        String treatyRef,
        UUID reinsurerId,
        String reinsurerName,
        BigDecimal sharePct,
        String status,
        UUID sourceEventId,
        OffsetDateTime occurredAt,
        BigDecimal nativeExpected,
        BigDecimal nativeReceived,
        BigDecimal participantExpected,
        BigDecimal participantReceived,
        String currencyCode,
        OffsetDateTime invoicedAt,
        OffsetDateTime receivedAt,
        String writeOffReason,
        OffsetDateTime createdAt,
        boolean priorPeriodAdjustment
) {
    public RecoveriesBordereauRow withPriorPeriodAdjustment(boolean flag) {
        return new RecoveriesBordereauRow(
                recoveryId, cessionId, treatyId, treatyRef,
                reinsurerId, reinsurerName, sharePct, status,
                sourceEventId, occurredAt, nativeExpected, nativeReceived,
                participantExpected, participantReceived, currencyCode,
                invoicedAt, receivedAt, writeOffReason, createdAt, flag);
    }
}

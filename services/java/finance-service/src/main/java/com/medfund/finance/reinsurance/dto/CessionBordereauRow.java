package com.medfund.finance.reinsurance.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One cession-per-participant row on the cession bordereau report. Rows
 * carry native amounts (R7); conversion to reporting currency happens in
 * the envelope's grand-total scalars, never in the row body. Per-participant
 * split ({@code participantCeded = cededAmount × sharePct / 100}) is
 * materialised at query time to keep the report flat and avoid re-joining
 * on the client (R9).
 *
 * <p>{@code priorPeriodAdjustment} is populated in the service layer by
 * comparing {@code createdAt} against the bordereau_period_export's
 * {@code firstExportedAt} — flagged when the cession row was created after
 * the first export of this quarter's bordereau.
 */
public record CessionBordereauRow(
        UUID cessionId,
        UUID treatyId,
        String treatyRef,
        String treatyType,
        UUID reinsurerId,
        String reinsurerName,
        BigDecimal sharePct,
        String shareRole,
        String cessionType,
        String source,
        OffsetDateTime occurredAt,
        UUID sourceEventId,
        String sourceEventType,
        BigDecimal nativeBasis,
        BigDecimal nativeCededFull,
        BigDecimal participantCeded,
        String currencyCode,
        OffsetDateTime createdAt,
        boolean priorPeriodAdjustment
) {
    public CessionBordereauRow withPriorPeriodAdjustment(boolean flag) {
        return new CessionBordereauRow(
                cessionId, treatyId, treatyRef, treatyType,
                reinsurerId, reinsurerName, sharePct, shareRole,
                cessionType, source, occurredAt, sourceEventId, sourceEventType,
                nativeBasis, nativeCededFull, participantCeded, currencyCode,
                createdAt, flag);
    }
}

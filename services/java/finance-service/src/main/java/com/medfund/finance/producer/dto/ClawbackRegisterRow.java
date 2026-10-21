package com.medfund.finance.producer.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One row per {@code clawback_event} in the reporting period. The register
 * folds both trigger sources into one flat view:
 * <ul>
 *   <li>{@code source = MEMBER_LAPSE} — the paying member lapsed / terminated
 *       within {@code rate_card.clawback_window_days} of the accrual.</li>
 *   <li>{@code source = CONTRIBUTION_REVOKE} — the underlying contribution
 *       was revoked and the accrual reversed.</li>
 * </ul>
 *
 * <p>{@code nativeAmount} is the reversed commission in the accrual's own
 * currency; the envelope's {@code perCurrency} + {@code fxRates} carry any
 * reporting-currency conversion the client wants to render.
 *
 * <p>{@code commissionReference} is the original accrual's friendly
 * reference ({@code COMM-YYYY-NNNNNN}) so a compliance reviewer can navigate
 * from the clawback back to the accrual it reversed.
 */
public record ClawbackRegisterRow(
        UUID clawbackEventId,
        String source,
        String triggeringEventRef,
        UUID memberId,
        UUID producerId,
        String producerCode,
        String producerName,
        UUID commissionTransactionId,
        String commissionReference,
        BigDecimal nativeAmount,
        String nativeCurrency,
        String reason,
        OffsetDateTime occurredAt
) {}

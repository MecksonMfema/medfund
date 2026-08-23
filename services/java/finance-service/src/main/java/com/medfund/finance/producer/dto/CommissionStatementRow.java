package com.medfund.finance.producer.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One row per {@code commission_transaction} in the reporting period, joined
 * with the producer and (optionally) the rate card that drove it. Rows stay
 * native (per parent-plan invariant #1 — cross-phase G6): the envelope's
 * {@code perCurrency} map + {@code fxRates} carry any reporting-currency
 * conversion the client wants to render on top.
 *
 * <p>{@code reference} is the friendly identifier ({@code COMM-YYYY-NNNNNN})
 * that shows on the XLSX and the audit trail. {@code producerCode} + {@code
 * producerName} are denormalised at query time so the report renders without
 * a second round-trip.
 */
public record CommissionStatementRow(
        UUID commissionTransactionId,
        String reference,
        UUID producerId,
        String producerCode,
        String producerName,
        String producerHomeCurrency,
        UUID contributionId,
        UUID memberId,
        String insuranceLine,
        UUID rateCardId,
        String rateCardName,
        BigDecimal appliedRatePct,
        BigDecimal contributionAmount,
        BigDecimal nativeAmount,
        String nativeCurrency,
        String status,
        OffsetDateTime occurredAt
) {}

package com.medfund.user.endorsement.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One row per endorsement in the reporting window per Phase 12 §C Phase 9.
 * Native-currency ({@code premiumDelta} + {@code currencyCode}); the
 * envelope's {@code perCurrency} totals aggregate {@code |premiumDelta|}
 * per currency so a mixed-currency register still sums.
 *
 * <p>Actor emails carry through so the register doubles as an audit trail
 * — the tenant admin can eyeball who drafted / approved / committed each
 * row without pivoting to the audit-service.
 */
public record EndorsementRegisterRow(
        UUID endorsementId,
        String reference,
        UUID policyId,
        String policySource,
        String memberName,
        String insuranceLine,
        String changeType,
        LocalDate effectiveFrom,
        BigDecimal premiumDelta,
        String currencyCode,
        String status,
        String draftActorEmail,
        Instant draftAt,
        String approveActorEmail,
        Instant approveAt,
        String commitActorEmail,
        Instant commitAt,
        String voidedReason
) {}

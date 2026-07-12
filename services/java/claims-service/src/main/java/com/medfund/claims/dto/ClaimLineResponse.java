package com.medfund.claims.dto;

import com.medfund.claims.entity.ClaimLine;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ClaimLineResponse(
        UUID id,
        UUID claimId,
        String tariffCode,
        String description,
        Integer quantity,
        BigDecimal unitPrice,
        BigDecimal claimedAmount,
        BigDecimal approvedAmount,
        String modifierCodes,
        String originalTariffCode,
        String originalModifierCodes,
        String currencyCode,
        String status,
        String rejectionReason,
        Instant createdAt
) {
    public static ClaimLineResponse from(ClaimLine line) {
        return new ClaimLineResponse(
                line.getId(),
                line.getClaimId(),
                line.getTariffCode(),
                line.getDescription(),
                line.getQuantity(),
                line.getUnitPrice(),
                line.getClaimedAmount(),
                line.getApprovedAmount(),
                line.getModifierCodes(),
                line.getOriginalTariffCode(),
                line.getOriginalModifierCodes(),
                line.getCurrencyCode(),
                line.getStatus(),
                line.getRejectionReason(),
                line.getCreatedAt()
        );
    }
}

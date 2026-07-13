package com.medfund.claims.dto;

import com.medfund.claims.entity.PreAuthorization;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record PreAuthResponse(
        UUID id,
        String authNumber,
        UUID memberId,
        UUID dependantId,
        UUID providerId,
        UUID schemeId,
        String tariffCode,
        String diagnosisCode,
        String status,
        BigDecimal requestedAmount,
        BigDecimal approvedAmount,
        String currencyCode,
        LocalDate requestedDate,
        LocalDate decisionDate,
        LocalDate expiryDate,
        UUID decisionBy,
        String notes,
        String rejectionReason,
        Instant createdAt,
        Instant updatedAt,
        UUID createdBy
) {
    public static PreAuthResponse from(PreAuthorization preAuth) {
        return new PreAuthResponse(
                preAuth.getId(),
                preAuth.getAuthNumber(),
                preAuth.getMemberId(),
                preAuth.getDependantId(),
                preAuth.getProviderId(),
                preAuth.getSchemeId(),
                preAuth.getTariffCode(),
                preAuth.getDiagnosisCode(),
                preAuth.getStatus(),
                preAuth.getRequestedAmount(),
                preAuth.getApprovedAmount(),
                preAuth.getCurrencyCode(),
                preAuth.getRequestedDate(),
                preAuth.getDecisionDate(),
                preAuth.getExpiryDate(),
                preAuth.getDecisionBy(),
                preAuth.getNotes(),
                preAuth.getRejectionReason(),
                preAuth.getCreatedAt(),
                preAuth.getUpdatedAt(),
                preAuth.getCreatedBy()
        );
    }
}

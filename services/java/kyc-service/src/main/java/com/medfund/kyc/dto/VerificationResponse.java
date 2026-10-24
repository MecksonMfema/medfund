package com.medfund.kyc.dto;

import com.medfund.kyc.entity.KycVerification;

import java.time.Instant;
import java.util.UUID;

/**
 * Party-neutral read model for a KYC verification. Line-neutral wording per
 * the framing rule — a {@code subject}, never a "patient". {@code insuranceLine}
 * is surfaced as contextual metadata only (G6).
 */
public record VerificationResponse(
        UUID id,
        String subjectType,
        UUID subjectId,
        String insuranceLine,
        String verificationStatus,
        Short verificationLevel,
        String method,
        String screeningResult,
        Instant verifiedAt,
        String verifiedByEmail,
        Instant createdAt,
        Instant updatedAt
) {
    public static VerificationResponse from(KycVerification v) {
        return new VerificationResponse(
                v.getId(),
                v.getSubjectType(),
                v.getSubjectId(),
                v.getInsuranceLine(),
                v.getVerificationStatus(),
                v.getVerificationLevel(),
                v.getMethod(),
                v.getScreeningResult(),
                v.getVerifiedAt(),
                v.getVerifiedByEmail(),
                v.getCreatedAt(),
                v.getUpdatedAt());
    }
}

package com.medfund.claims.dto;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.claims.entity.Claim;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ClaimResponse(
        UUID id,
        String claimNumber,
        UUID memberId,
        UUID dependantId,
        UUID providerId,
        UUID schemeId,
        UUID benefitId,
        String claimType,
        String insuranceLine,
        String batchNumber,
        String status,
        LocalDate serviceDate,
        Instant submissionDate,
        BigDecimal claimedAmount,
        BigDecimal approvedAmount,
        BigDecimal paidAmount,
        String currencyCode,
        String diagnosisCodes,
        String procedureCodes,
        String notes,
        String rejectionReason,
        String rejectionNotes,
        String verificationCode,
        Instant verifiedAt,
        Instant adjudicatedAt,
        UUID adjudicatedBy,
        Instant createdAt,
        Instant updatedAt,
        UUID createdBy,
        UUID updatedBy,
        List<ClaimAttachment> attachments
) {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<ClaimAttachment>> ATTACHMENT_LIST =
            new TypeReference<>() {};

    public static ClaimResponse from(Claim claim) {
        return new ClaimResponse(
                claim.getId(),
                claim.getClaimNumber(),
                claim.getMemberId(),
                claim.getDependantId(),
                claim.getProviderId(),
                claim.getSchemeId(),
                claim.getBenefitId(),
                claim.getClaimType(),
                claim.getInsuranceLine(),
                claim.getBatchNumber(),
                claim.getStatus(),
                claim.getServiceDate(),
                claim.getSubmissionDate(),
                claim.getClaimedAmount(),
                claim.getApprovedAmount(),
                claim.getPaidAmount(),
                claim.getCurrencyCode(),
                claim.getDiagnosisCodes(),
                claim.getProcedureCodes(),
                claim.getNotes(),
                claim.getRejectionReason(),
                claim.getRejectionNotes(),
                claim.getVerificationCode(),
                claim.getVerifiedAt(),
                claim.getAdjudicatedAt(),
                claim.getAdjudicatedBy(),
                claim.getCreatedAt(),
                claim.getUpdatedAt(),
                claim.getCreatedBy(),
                claim.getUpdatedBy(),
                parseAttachments(claim.getAttachmentsJson())
        );
    }

    private static List<ClaimAttachment> parseAttachments(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return MAPPER.readValue(json, ATTACHMENT_LIST);
        } catch (Exception e) {
            // Bad JSON in the DB is a data-migration escape hatch — hand
            // back an empty list rather than 500ing the whole claim.
            return List.of();
        }
    }
}

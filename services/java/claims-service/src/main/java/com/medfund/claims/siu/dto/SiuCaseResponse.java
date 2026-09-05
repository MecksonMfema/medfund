package com.medfund.claims.siu.dto;

import com.medfund.claims.siu.entity.SiuCase;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Full case-detail shape. Linked {@code flags}, {@code notes},
 * {@code evidence}, and {@code referrals} are joined server-side by
 * {@link com.medfund.claims.siu.service.SiuCaseQueryService} so the
 * client renders a single request. §B Phase 8 adds the {@code proposed_*}
 * four-eyes staging fields; §B Phase 10 inlines evidence + referrals.
 */
public record SiuCaseResponse(
        UUID id,
        String caseNumber,
        String status,
        String priority,
        String[] tags,
        UUID assignedTo,
        UUID openedBy,
        String openedByEmail,
        OffsetDateTime openedAt,
        UUID closedBy,
        String closedByEmail,
        OffsetDateTime closedAt,
        String closureReason,
        String outcome,
        BigDecimal savedAmount,
        String savedCurrency,
        UUID proposedBy,
        String proposedByEmail,
        OffsetDateTime proposedAt,
        String proposedOutcome,
        BigDecimal proposedSavedAmount,
        String proposedSavedCurrency,
        String proposedClosureReason,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        List<FraudFlagResponse> flags,
        List<SiuCaseNoteResponse> notes,
        List<SiuEvidenceResponse> evidence,
        List<SiuReferralResponse> referrals
) {
    public static SiuCaseResponse from(SiuCase kase,
                                       List<FraudFlagResponse> flags,
                                       List<SiuCaseNoteResponse> notes,
                                       List<SiuEvidenceResponse> evidence,
                                       List<SiuReferralResponse> referrals) {
        return new SiuCaseResponse(
                kase.getId(),
                kase.getCaseNumber(),
                kase.getStatus(),
                kase.getPriority(),
                kase.getTags(),
                kase.getAssignedTo(),
                kase.getOpenedBy(),
                kase.getOpenedByEmail(),
                kase.getOpenedAt(),
                kase.getClosedBy(),
                kase.getClosedByEmail(),
                kase.getClosedAt(),
                kase.getClosureReason(),
                kase.getOutcome(),
                kase.getSavedAmount(),
                kase.getSavedCurrency(),
                kase.getProposedBy(),
                kase.getProposedByEmail(),
                kase.getProposedAt(),
                kase.getProposedOutcome(),
                kase.getProposedSavedAmount(),
                kase.getProposedSavedCurrency(),
                kase.getProposedClosureReason(),
                kase.getCreatedAt(),
                kase.getUpdatedAt(),
                flags,
                notes,
                evidence,
                referrals
        );
    }
}

package com.medfund.claims.dto;

import com.medfund.claims.entity.Claim;
import com.medfund.claims.service.CarcRarcMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read-only EOB view for a single adjudicated claim. Composed from the
 * persisted V077 cost-share columns on {@code claims} and the
 * {@link CarcRarcMapper} lookup for the CARC/RARC pairs member-facing
 * surfaces render as tooltips.
 *
 * <p>Returns null-safe zero values on legacy pre-V077 claims so the
 * member portal can still render the "breakdown unavailable" banner
 * without a defensive null-check per bucket.
 */
public record ClaimEobResponse(
        UUID claimId,
        String claimNumber,
        UUID memberId,
        UUID dependantId,
        String status,
        String decision,
        Instant adjudicatedAt,
        String currencyCode,
        BigDecimal allowedAmount,
        BigDecimal deductibleApplied,
        BigDecimal copayAmount,
        BigDecimal coinsuranceAmount,
        BigDecimal notCoveredAmount,
        BigDecimal shortfallAmount,
        BigDecimal memberResponsibility,
        BigDecimal planPaid,
        boolean breakdownAvailable,
        List<CarcRarcMapper.CarcRarc> reasonCodes
) {
    public static ClaimEobResponse from(Claim c) {
        boolean hasBreakdown = c.getAllowedAmount() != null || c.getMemberResponsibility() != null;
        BigDecimal allowed = c.getAllowedAmount();
        BigDecimal memberResp = c.getMemberResponsibility();
        BigDecimal planPaid = (allowed != null && memberResp != null)
                ? allowed.subtract(memberResp).max(BigDecimal.ZERO)
                : c.getApprovedAmount();
        List<CarcRarcMapper.CarcRarc> codes = hasBreakdown
                ? CarcRarcMapper.forCostShare(
                        plain(c.getDeductibleApplied()),
                        plain(c.getCopayAmount()),
                        plain(c.getCoinsuranceAmount()),
                        plain(c.getNotCoveredAmount()),
                        plain(c.getShortfallAmount()))
                : List.of();
        return new ClaimEobResponse(
                c.getId(),
                c.getClaimNumber(),
                c.getMemberId(),
                c.getDependantId(),
                c.getStatus(),
                statusToDecision(c.getStatus()),
                c.getAdjudicatedAt(),
                c.getCurrencyCode(),
                allowed,
                c.getDeductibleApplied(),
                c.getCopayAmount(),
                c.getCoinsuranceAmount(),
                c.getNotCoveredAmount(),
                c.getShortfallAmount(),
                memberResp,
                planPaid,
                hasBreakdown,
                codes);
    }

    /** Best-effort inverse of {@code AdjudicationDecisionEngine.decide} —
     *  ADJUDICATED persists as either APPROVED or PARTIAL_APPROVED; without
     *  a persisted decision column we surface the terminal status verbatim. */
    private static String statusToDecision(String status) {
        if (status == null) return null;
        return switch (status.toUpperCase()) {
            case "ADJUDICATED", "PAID", "COMMITTED" -> "APPROVED";
            case "REJECTED" -> "REJECTED";
            case "PENDING_INFO" -> "MANUAL_REVIEW";
            default -> status;
        };
    }

    private static String plain(BigDecimal v) {
        return v != null ? v.toPlainString() : null;
    }
}

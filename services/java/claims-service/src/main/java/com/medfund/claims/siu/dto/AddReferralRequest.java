package com.medfund.claims.siu.dto;

/**
 * Payload for {@code POST /api/v1/siu/cases/{caseId}/referrals}.
 * {@code referralTo} must be one of LAW_ENFORCEMENT | REGULATOR |
 * INTERNAL_HR. {@code referralReference} is the external body's own
 * case number if the caller has one at referral time; otherwise null
 * and updated later via a {@code response-notes} follow-up (§B Phase 10).
 */
public record AddReferralRequest(
        String referralTo,
        String referralReference
) {
}

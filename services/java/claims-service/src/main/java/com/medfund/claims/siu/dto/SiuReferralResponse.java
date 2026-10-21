package com.medfund.claims.siu.dto;

import com.medfund.claims.siu.entity.SiuReferral;

import java.time.OffsetDateTime;
import java.util.UUID;

public record SiuReferralResponse(
        UUID id,
        UUID caseId,
        String referralTo,
        String referralReference,
        UUID referredBy,
        String referredByEmail,
        OffsetDateTime referredAt,
        OffsetDateTime responseReceivedAt,
        String responseNotes
) {
    public static SiuReferralResponse from(SiuReferral r) {
        return new SiuReferralResponse(
                r.getId(),
                r.getCaseId(),
                r.getReferralTo(),
                r.getReferralReference(),
                r.getReferredBy(),
                r.getReferredByEmail(),
                r.getReferredAt(),
                r.getResponseReceivedAt(),
                r.getResponseNotes()
        );
    }
}

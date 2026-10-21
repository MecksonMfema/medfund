package com.medfund.finance.reinsurance.dto;

import com.medfund.finance.reinsurance.entity.TreatyParticipant;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record TreatyParticipantResponse(
        UUID treatyId,
        UUID reinsurerId,
        String reinsurerName,
        BigDecimal sharePct,
        String shareRole,
        OffsetDateTime createdAt
) {
    public static TreatyParticipantResponse from(TreatyParticipant p, String reinsurerName) {
        return new TreatyParticipantResponse(
                p.getTreatyId(), p.getReinsurerId(), reinsurerName,
                p.getSharePct(), p.getShareRole(), p.getCreatedAt());
    }
}

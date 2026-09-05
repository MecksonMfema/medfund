package com.medfund.claims.siu.dto;

import com.medfund.claims.siu.entity.FraudFlag;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record FraudFlagResponse(
        UUID id,
        UUID claimId,
        UUID siuCaseId,
        String flagSource,
        String modelVersion,
        BigDecimal riskScore,
        String riskLevel,
        String indicatorsJson,
        OffsetDateTime flaggedAt
) {
    public static FraudFlagResponse from(FraudFlag flag) {
        return new FraudFlagResponse(
                flag.getId(),
                flag.getClaimId(),
                flag.getSiuCaseId(),
                flag.getFlagSource(),
                flag.getModelVersion(),
                flag.getRiskScore(),
                flag.getRiskLevel(),
                flag.getIndicators() == null ? "[]" : flag.getIndicators().asString(),
                flag.getFlaggedAt()
        );
    }
}

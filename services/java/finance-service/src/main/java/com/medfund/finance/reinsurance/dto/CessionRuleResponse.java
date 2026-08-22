package com.medfund.finance.reinsurance.dto;

import com.medfund.finance.reinsurance.entity.CessionRule;

import java.time.OffsetDateTime;
import java.util.UUID;

public record CessionRuleResponse(
        UUID id,
        UUID treatyId,
        UUID ruleDefinitionId,
        Boolean enabled,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static CessionRuleResponse from(CessionRule r) {
        return new CessionRuleResponse(
                r.getId(), r.getTreatyId(), r.getRuleDefinitionId(),
                r.getEnabled(), r.getCreatedAt(), r.getUpdatedAt());
    }
}

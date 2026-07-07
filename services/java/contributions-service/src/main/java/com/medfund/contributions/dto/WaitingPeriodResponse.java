package com.medfund.contributions.dto;

import com.medfund.contributions.entity.WaitingPeriodRule;

import java.time.Instant;
import java.util.UUID;

public record WaitingPeriodResponse(
        UUID id,
        UUID schemeId,
        String conditionType,
        Integer waitingDays,
        String description,
        Short minAge,
        Short maxAge,
        Instant createdAt
) {
    public static WaitingPeriodResponse from(WaitingPeriodRule r) {
        return new WaitingPeriodResponse(
                r.getId(), r.getSchemeId(), r.getConditionType(),
                r.getWaitingDays(), r.getDescription(),
                r.getMinAge(), r.getMaxAge(),
                r.getCreatedAt());
    }
}

package com.medfund.contributions.dto;

import com.medfund.contributions.entity.SchemeChangeWaitingPeriodRule;

import java.time.Instant;
import java.util.UUID;

public record SchemeChangeWaitingPeriodResponse(
        UUID id,
        String changeType,
        String benefitType,
        Integer waitingDays,
        String description,
        Boolean isActive,
        Instant updatedAt
) {
    public static SchemeChangeWaitingPeriodResponse from(SchemeChangeWaitingPeriodRule r) {
        return new SchemeChangeWaitingPeriodResponse(
                r.getId(), r.getChangeType(), r.getBenefitType(),
                r.getWaitingDays(), r.getDescription(), r.getIsActive(), r.getUpdatedAt());
    }
}

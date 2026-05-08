package com.medfund.contributions.dto;

import com.medfund.contributions.entity.BenefitType;

import java.time.Instant;
import java.util.UUID;

public record BenefitTypeResponse(
        UUID id,
        String code,
        String label,
        String description,
        Integer sortOrder,
        Boolean isActive,
        Instant updatedAt
) {
    public static BenefitTypeResponse from(BenefitType b) {
        return new BenefitTypeResponse(b.getId(), b.getCode(), b.getLabel(), b.getDescription(),
                b.getSortOrder(), b.getIsActive(), b.getUpdatedAt());
    }
}

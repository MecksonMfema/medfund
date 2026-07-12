package com.medfund.claims.dto;

import com.medfund.claims.entity.TariffCategory;

import java.time.Instant;
import java.util.UUID;

public record TariffCategoryResponse(
        UUID id,
        String code,
        String label,
        String description,
        Boolean isCapOnly,
        Boolean isActive,
        Integer sortOrder,
        Instant createdAt,
        Instant updatedAt
) {
    public static TariffCategoryResponse from(TariffCategory c) {
        return new TariffCategoryResponse(
                c.getId(), c.getCode(), c.getLabel(), c.getDescription(),
                Boolean.TRUE.equals(c.getIsCapOnly()),
                c.getIsActive() == null || Boolean.TRUE.equals(c.getIsActive()),
                c.getSortOrder() != null ? c.getSortOrder() : 0,
                c.getCreatedAt(), c.getUpdatedAt());
    }
}

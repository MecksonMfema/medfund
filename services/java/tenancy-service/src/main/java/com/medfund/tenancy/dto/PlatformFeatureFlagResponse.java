package com.medfund.tenancy.dto;

import com.medfund.shared.flags.PlatformFlag;
import com.medfund.tenancy.entity.PlatformFeatureFlag;

import java.time.OffsetDateTime;

public record PlatformFeatureFlagResponse(
        String key,
        String name,
        String description,
        Boolean enabled,
        OffsetDateTime updatedAt,
        String updatedBy
) {
    public static PlatformFeatureFlagResponse from(PlatformFeatureFlag row, PlatformFlag meta) {
        return new PlatformFeatureFlagResponse(
                row.getKey(),
                meta.displayName(),
                meta.description(),
                row.getEnabled(),
                row.getUpdatedAt(),
                row.getUpdatedBy()
        );
    }
}

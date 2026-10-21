package com.medfund.finance.reinsurance.dto;

import com.medfund.finance.reinsurance.entity.Reinsurer;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ReinsurerResponse(
        UUID id,
        String name,
        String contactEmail,
        String contactAddress,
        String jurisdictionCode,
        String homeCurrency,
        String creditRating,
        Boolean active,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static ReinsurerResponse from(Reinsurer r) {
        return new ReinsurerResponse(
                r.getId(), r.getName(), r.getContactEmail(), r.getContactAddress(),
                r.getJurisdictionCode(), r.getHomeCurrency(), r.getCreditRating(),
                r.getActive(), r.getCreatedAt(), r.getUpdatedAt());
    }
}

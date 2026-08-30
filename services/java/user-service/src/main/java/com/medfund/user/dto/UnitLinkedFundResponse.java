package com.medfund.user.dto;

import com.medfund.user.entity.UnitLinkedFund;

import java.time.Instant;
import java.util.UUID;

public record UnitLinkedFundResponse(
        UUID id,
        String name,
        String currency,
        String baseAssetClass,
        Boolean isActive,
        Instant createdAt,
        Instant updatedAt,
        UUID actorId,
        String actorEmail
) {
    public static UnitLinkedFundResponse from(UnitLinkedFund f) {
        return new UnitLinkedFundResponse(
                f.getId(), f.getName(), f.getCurrency(), f.getBaseAssetClass(),
                f.getIsActive(), f.getCreatedAt(), f.getUpdatedAt(),
                f.getActorId(), f.getActorEmail()
        );
    }
}

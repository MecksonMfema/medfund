package com.medfund.claims.dto;

import com.medfund.claims.entity.RejectionReason;

import java.util.UUID;

public record RejectionReasonResponse(
        UUID id,
        String code,
        String description,
        String category,
        Boolean isActive
) {
    public static RejectionReasonResponse from(RejectionReason r) {
        return new RejectionReasonResponse(
                r.getId(), r.getCode(), r.getDescription(), r.getCategory(), r.getIsActive());
    }
}

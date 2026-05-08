package com.medfund.contributions.dto;

import com.medfund.contributions.entity.TransactionType;

import java.time.Instant;
import java.util.UUID;

public record TransactionTypeResponse(
        UUID id,
        String code,
        String label,
        String description,
        String sign,
        Boolean requiresApproval,
        Boolean isActive,
        Instant updatedAt
) {
    public static TransactionTypeResponse from(TransactionType t) {
        return new TransactionTypeResponse(t.getId(), t.getCode(), t.getLabel(), t.getDescription(),
                t.getSign(), t.getRequiresApproval(), t.getIsActive(), t.getUpdatedAt());
    }
}

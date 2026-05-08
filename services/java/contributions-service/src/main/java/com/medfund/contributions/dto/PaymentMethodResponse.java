package com.medfund.contributions.dto;

import com.medfund.contributions.entity.PaymentMethod;

import java.time.Instant;
import java.util.UUID;

public record PaymentMethodResponse(
        UUID id,
        String code,
        String label,
        String description,
        Boolean requiresReference,
        Boolean isActive,
        Instant updatedAt
) {
    public static PaymentMethodResponse from(PaymentMethod p) {
        return new PaymentMethodResponse(p.getId(), p.getCode(), p.getLabel(), p.getDescription(),
                p.getRequiresReference(), p.getIsActive(), p.getUpdatedAt());
    }
}

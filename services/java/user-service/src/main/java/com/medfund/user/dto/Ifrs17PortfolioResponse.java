package com.medfund.user.dto;

import com.medfund.user.entity.Ifrs17Portfolio;

import java.time.Instant;
import java.util.UUID;

public record Ifrs17PortfolioResponse(
    UUID id,
    String name,
    String description,
    String insuranceLine,
    Boolean isActive,
    Instant createdAt,
    Instant updatedAt
) {
    public static Ifrs17PortfolioResponse from(Ifrs17Portfolio p) {
        return new Ifrs17PortfolioResponse(
            p.getId(), p.getName(), p.getDescription(),
            p.getInsuranceLine(), p.getIsActive(),
            p.getCreatedAt(), p.getUpdatedAt()
        );
    }
}

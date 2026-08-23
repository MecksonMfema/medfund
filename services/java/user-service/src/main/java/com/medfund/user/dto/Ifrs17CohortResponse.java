package com.medfund.user.dto;

import com.medfund.user.entity.Ifrs17Cohort;

import java.time.Instant;
import java.util.UUID;

public record Ifrs17CohortResponse(
    UUID id,
    UUID portfolioId,
    Integer cohortYear,
    String cohortType,
    String name,
    Boolean isActive,
    Instant createdAt,
    Instant updatedAt
) {
    public static Ifrs17CohortResponse from(Ifrs17Cohort c) {
        return new Ifrs17CohortResponse(
            c.getId(), c.getPortfolioId(), c.getCohortYear(),
            c.getCohortType(), c.getName(), c.getIsActive(),
            c.getCreatedAt(), c.getUpdatedAt()
        );
    }
}

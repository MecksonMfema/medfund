package com.medfund.user.dto;

import com.medfund.user.entity.Ifrs17OpeningBalanceSeed;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record Ifrs17OpeningBalanceSeedResponse(
        UUID id,
        UUID portfolioId,
        UUID cohortId,
        String currency,
        String balanceType,
        BigDecimal amount,
        LocalDate effectiveFrom,
        String reasonNote,
        UUID actorId,
        String actorEmail,
        Instant createdAt
) {
    public static Ifrs17OpeningBalanceSeedResponse from(Ifrs17OpeningBalanceSeed s) {
        return new Ifrs17OpeningBalanceSeedResponse(
                s.getId(),
                s.getPortfolioId(),
                s.getCohortId(),
                s.getCurrency(),
                s.getBalanceType(),
                s.getAmount(),
                s.getEffectiveFrom(),
                s.getReasonNote(),
                s.getActorId(),
                s.getActorEmail(),
                s.getCreatedAt()
        );
    }
}

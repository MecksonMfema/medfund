package com.medfund.user.dto;

import com.medfund.user.entity.FundNavHistory;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record FundNavHistoryResponse(
        UUID id,
        UUID fundId,
        LocalDate valuationDate,
        BigDecimal navPerUnit,
        String source,
        Instant createdAt,
        UUID actorId,
        String actorEmail
) {
    public static FundNavHistoryResponse from(FundNavHistory h) {
        return new FundNavHistoryResponse(
                h.getId(), h.getFundId(), h.getValuationDate(), h.getNavPerUnit(),
                h.getSource(), h.getCreatedAt(), h.getActorId(), h.getActorEmail()
        );
    }
}

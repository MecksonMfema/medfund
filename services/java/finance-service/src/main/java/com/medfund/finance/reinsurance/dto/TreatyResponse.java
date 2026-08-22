package com.medfund.finance.reinsurance.dto;

import com.medfund.finance.reinsurance.entity.Treaty;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record TreatyResponse(
        UUID id,
        String treatyRef,
        String treatyType,
        String declaredCurrency,
        LocalDate inceptionDate,
        LocalDate expiryDate,
        String status,
        UUID renewedFromTreatyId,
        BigDecimal aggregateLimit,
        String aggregateLimitCurrency,
        BigDecimal expectedAnnualPremium,
        String producerRef,
        OffsetDateTime activatedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static TreatyResponse from(Treaty t) {
        return new TreatyResponse(
                t.getId(), t.getTreatyRef(), t.getTreatyType(), t.getDeclaredCurrency(),
                t.getInceptionDate(), t.getExpiryDate(), t.getStatus(),
                t.getRenewedFromTreatyId(), t.getAggregateLimit(), t.getAggregateLimitCurrency(),
                t.getExpectedAnnualPremium(), t.getProducerRef(), t.getActivatedAt(),
                t.getCreatedAt(), t.getUpdatedAt());
    }
}

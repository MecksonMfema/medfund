package com.medfund.finance.producer.dto;

import com.medfund.finance.producer.entity.CommissionRateCard;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record RateCardResponse(
        UUID id,
        String name,
        String insuranceLine,
        String producerTier,
        BigDecimal baseRatePct,
        Integer clawbackWindowDays,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        Boolean active,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static RateCardResponse from(CommissionRateCard c) {
        return new RateCardResponse(
                c.getId(), c.getName(), c.getInsuranceLine(), c.getProducerTier(),
                c.getBaseRatePct(), c.getClawbackWindowDays(),
                c.getEffectiveFrom(), c.getEffectiveTo(), c.getActive(),
                c.getCreatedAt(), c.getUpdatedAt());
    }
}

package com.medfund.user.dto;

import com.medfund.user.entity.VariableFeeSchedule;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record VariableFeeScheduleResponse(
        UUID id,
        UUID fundId,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        BigDecimal feePercentage,
        Instant createdAt,
        UUID actorId,
        String actorEmail
) {
    public static VariableFeeScheduleResponse from(VariableFeeSchedule s) {
        return new VariableFeeScheduleResponse(
                s.getId(), s.getFundId(), s.getEffectiveFrom(), s.getEffectiveTo(),
                s.getFeePercentage(), s.getCreatedAt(), s.getActorId(), s.getActorEmail()
        );
    }
}

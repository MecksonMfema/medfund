package com.medfund.user.dto;

import com.medfund.user.entity.PolicyUnitLedger;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record PolicyUnitLedgerResponse(
        UUID id,
        UUID policyId,
        UUID fundId,
        LocalDate transactionDate,
        String transactionType,
        BigDecimal units,
        BigDecimal price,
        Instant createdAt,
        UUID actorId,
        String actorEmail
) {
    public static PolicyUnitLedgerResponse from(PolicyUnitLedger l) {
        return new PolicyUnitLedgerResponse(
                l.getId(), l.getPolicyId(), l.getFundId(), l.getTransactionDate(),
                l.getTransactionType(), l.getUnits(), l.getPrice(),
                l.getCreatedAt(), l.getActorId(), l.getActorEmail()
        );
    }
}

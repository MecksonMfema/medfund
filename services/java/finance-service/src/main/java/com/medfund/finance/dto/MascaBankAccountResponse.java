package com.medfund.finance.dto;

import com.medfund.finance.entity.MascaBankAccount;

import java.time.Instant;
import java.util.UUID;

public record MascaBankAccountResponse(
    UUID id,
    String bankName,
    String accountNumber,
    String branchCode,
    String swiftCode,
    String accountName,
    String currencyCode,
    Boolean nominated,
    Boolean active,
    Instant createdAt,
    Instant updatedAt
) {
    public static MascaBankAccountResponse from(MascaBankAccount a) {
        return new MascaBankAccountResponse(
            a.getId(),
            a.getBankName(),
            a.getAccountNumber(),
            a.getBranchCode(),
            a.getSwiftCode(),
            a.getAccountName(),
            a.getCurrencyCode(),
            a.getNominated(),
            a.getActive(),
            a.getCreatedAt(),
            a.getUpdatedAt()
        );
    }
}

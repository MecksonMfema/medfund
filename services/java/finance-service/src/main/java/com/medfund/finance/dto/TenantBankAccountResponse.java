package com.medfund.finance.dto;

import com.medfund.finance.entity.TenantBankAccount;

import java.time.Instant;
import java.util.UUID;

public record TenantBankAccountResponse(
    UUID id,
    String bankName,
    String accountNumber,
    String branchCode,
    String swiftCode,
    String accountName,
    String currencyCode,
    String label,
    String notes,
    Boolean nominated,
    Boolean active,
    Instant createdAt,
    Instant updatedAt
) {
    public static TenantBankAccountResponse from(TenantBankAccount a) {
        return new TenantBankAccountResponse(
            a.getId(),
            a.getBankName(),
            a.getAccountNumber(),
            a.getBranchCode(),
            a.getSwiftCode(),
            a.getAccountName(),
            a.getCurrencyCode(),
            a.getLabel(),
            a.getNotes(),
            a.getNominated(),
            a.getActive(),
            a.getCreatedAt(),
            a.getUpdatedAt()
        );
    }
}

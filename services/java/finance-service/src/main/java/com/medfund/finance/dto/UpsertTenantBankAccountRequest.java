package com.medfund.finance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpsertTenantBankAccountRequest(
    @NotBlank @Size(max = 200) String bankName,
    @NotBlank @Size(max = 50) String accountNumber,
    @Size(max = 50) String branchCode,
    @Size(max = 50) String swiftCode,
    @NotBlank @Size(max = 200) String accountName,
    @NotBlank @Size(max = 3) String currencyCode,
    @NotBlank @Size(max = 120) String label,
    @Size(max = 4000) String notes,
    Boolean nominated,
    Boolean active
) {}

package com.medfund.finance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpsertMascaBankAccountRequest(
    @NotBlank @Size(max = 200) String bankName,
    @NotBlank @Size(max = 100) String accountNumber,
    @Size(max = 50) String branchCode,
    @Size(max = 20) String swiftCode,
    @NotBlank @Size(max = 200) String accountName,
    @NotBlank @Size(max = 3) String currencyCode,
    Boolean nominated,
    Boolean active
) {}

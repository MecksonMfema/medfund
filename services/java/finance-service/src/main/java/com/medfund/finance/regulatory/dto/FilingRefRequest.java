package com.medfund.finance.regulatory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Capture the regulator-assigned filing reference after portal upload. */
public record FilingRefRequest(
        @NotBlank @Size(max = 200) String filingRef
) {}

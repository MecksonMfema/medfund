package com.medfund.finance.regulatory.aml.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Compliance filing payload for the REVIEWED → FILED transition.
 * {@code filedRef} is the regulator-assigned filing reference (FIU / FIC / FinCEN)
 * captured post-submission. {@code filedXlsxRef} is optional at this stage —
 * Phase 26 generates the XLSX and populates it via a dedicated endpoint.
 */
public record FileAmlAlertRequest(
        @NotBlank @Size(max = 120) String filedRef,
        @Size(max = 255) String filedXlsxRef
) {}

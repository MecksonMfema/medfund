package com.medfund.finance.regulatory.aml.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * RAISED|REVIEWED → CLOSED transition payload. Reason is required so the
 * "not reportable" call has an audit trail.
 */
public record CloseAmlAlertRequest(
        @NotBlank @Size(min = 5, max = 120,
                message = "closedReason is required")
        String closedReason
) {}

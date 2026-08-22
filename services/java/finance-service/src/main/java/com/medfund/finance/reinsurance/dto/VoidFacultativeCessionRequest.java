package com.medfund.finance.reinsurance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Supervisor-supplied body for {@code DELETE /api/v1/reinsurance/facultative/{id}}.
 * Reason is mandatory — a voided facultative cession without an audit
 * trail of *why* is exactly the surprise that gets flagged in a
 * reinsurance settlement dispute.
 */
public record VoidFacultativeCessionRequest(
        @NotBlank @Size(max = 500) String reason
) {}

package com.medfund.claims.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * POST /api/v1/eligibility-quote payload. The provider names the member by
 * their friendly {@code memberNumber} (per {@code feedback_no_raw_id_inputs}
 * — the wire carries the string, never a raw member UUID) and lists the
 * tariff codes they intend to bill. The service resolves the member,
 * runs a read-only adjudication, and returns the seven cost-share buckets
 * for the intended service.
 */
public record EligibilityQuoteRequest(
        @NotBlank String memberNumber,
        @NotBlank String serviceCategory,
        @NotEmpty List<@NotBlank String> tariffCodes,
        @NotNull @DecimalMin("0.01") BigDecimal billedAmount,
        @NotBlank @Size(min = 3, max = 3) String currencyCode,
        @NotNull LocalDate dateOfService
) {
}

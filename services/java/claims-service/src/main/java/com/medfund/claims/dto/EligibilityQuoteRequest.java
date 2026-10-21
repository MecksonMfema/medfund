package com.medfund.claims.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * POST /api/v1/eligibility-quote payload. The provider names the sponsoring
 * member by their friendly {@code memberNumber} (per
 * {@code feedback_no_raw_id_inputs} - the wire carries the string, never a
 * raw member UUID) and lists the tariff codes they intend to bill. When the
 * quote is for a dependant, the caller ALSO sets {@link #dependantId} to the
 * dependant's UUID; {@code memberNumber} still names the sponsor. Same shape
 * as {@link SubmitClaimRequest} and {@link PreAuthRequest}. The service
 * resolves the sponsor (and dependant when present), runs a read-only
 * adjudication, and returns the seven cost-share buckets for the intended
 * service.
 */
public record EligibilityQuoteRequest(
        @NotBlank String memberNumber,
        @Schema(description = "Optional. When present, the quote is scoped to this dependant "
                + "of the member named by memberNumber. UUID matches SubmitClaimRequest / "
                + "PreAuthRequest.")
        UUID dependantId,
        @NotBlank String serviceCategory,
        @NotEmpty List<@NotBlank String> tariffCodes,
        @NotNull @DecimalMin("0.01") BigDecimal billedAmount,
        @NotBlank @Size(min = 3, max = 3) String currencyCode,
        @NotNull LocalDate dateOfService
) {
}

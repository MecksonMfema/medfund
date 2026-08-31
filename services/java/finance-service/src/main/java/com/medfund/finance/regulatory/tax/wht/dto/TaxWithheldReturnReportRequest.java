package com.medfund.finance.regulatory.tax.wht.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * Request body for {@code POST /api/v1/reports/regulatory/tax/withheld-return/submit}.
 *
 * <p>{@link #reportingCurrency} is ignored — currency follows
 * tenant.country_code (ZWL / ZAR). {@link #submit}=true archives to
 * {@code regulatory_submission} (requires fresh MFA on the JWT).
 */
public record TaxWithheldReturnReportRequest(
        @NotNull LocalDate periodStart,
        @NotNull LocalDate periodEnd,
        @JsonProperty("reportingCurrency") String reportingCurrency,
        @JsonProperty("submit") boolean submit,
        @JsonProperty("attestationNote") String attestationNote) {}

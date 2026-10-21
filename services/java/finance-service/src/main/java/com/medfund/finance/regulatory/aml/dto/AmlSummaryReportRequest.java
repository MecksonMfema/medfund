package com.medfund.finance.regulatory.aml.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * Request body for {@code POST /api/v1/reports/regulatory/aml-str/periodic/submit}.
 *
 * <p>{@link #reportingCurrency} is ignored — the currency for the
 * periodic AML summary is country-native (ZWL / ZAR / USD) and a
 * non-blank override is rejected 422 by the shape service.
 *
 * <p>{@link #submit} = {@code true} archives the composed XLSX into
 * {@code regulatory_submission} with MFA step-up on the JWT. Default is
 * {@code false} — dry-run export for compliance review.
 */
public record AmlSummaryReportRequest(
        @NotNull LocalDate periodStart,
        @NotNull LocalDate periodEnd,
        @JsonProperty("reportingCurrency") String reportingCurrency,
        @JsonProperty("submit") boolean submit,
        @JsonProperty("attestationNote") String attestationNote) {}

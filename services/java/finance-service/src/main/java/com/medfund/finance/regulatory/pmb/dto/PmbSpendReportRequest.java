package com.medfund.finance.regulatory.pmb.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * Request body for {@code POST /api/v1/reports/regulatory/pmb/spend/submit}.
 *
 * <p>{@link #periodStart} + {@link #periodEnd} identify the reporting
 * period — CMS PMB spend is typically annual, but tenants may run partial
 * dry-runs for compliance review.
 *
 * <p>{@link #reportingCurrency} is ignored for PMB spend — the currency is
 * fixed at ZAR by {@link com.medfund.shared.report.regulatory.RegulatoryReportCurrency}
 * and a non-blank override is rejected with 422 upstream by the shape
 * service. The field is accepted (rather than removed) so the DTO shape
 * stays parallel to the IPEC / CMS / NAIC requests.
 *
 * <p>{@link #submit} = {@code true} archives the composed XLSX into
 * {@code regulatory_submission} (with MFA step-up on the JWT). Default is
 * {@code false} — dry-run export for compliance review.
 */
public record PmbSpendReportRequest(
        @NotNull LocalDate periodStart,
        @NotNull LocalDate periodEnd,
        @JsonProperty("reportingCurrency") String reportingCurrency,
        @JsonProperty("submit") boolean submit,
        @JsonProperty("attestationNote") String attestationNote) {}

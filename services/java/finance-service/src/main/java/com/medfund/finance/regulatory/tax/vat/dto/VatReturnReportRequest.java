package com.medfund.finance.regulatory.tax.vat.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * Request body for {@code POST /api/v1/reports/regulatory/tax/vat-return/submit}.
 *
 * <p>{@link #reportingCurrency} is ignored — the currency follows the
 * tenant's country per
 * {@link com.medfund.shared.report.regulatory.RegulatoryReportCurrency}
 * (ZWL for ZW, ZAR for ZA) and a non-blank override is rejected 422
 * upstream by the shape service. The field is accepted (rather than
 * removed) so the DTO shape stays parallel to the IPEC / CMS / NAIC
 * / PMB requests.
 *
 * <p>{@link #submit} = {@code true} archives the composed XLSX into
 * {@code regulatory_submission} (with MFA step-up on the JWT). Default
 * is {@code false} — dry-run export for compliance review.
 */
public record VatReturnReportRequest(
        @NotNull LocalDate periodStart,
        @NotNull LocalDate periodEnd,
        @JsonProperty("reportingCurrency") String reportingCurrency,
        @JsonProperty("submit") boolean submit,
        @JsonProperty("attestationNote") String attestationNote) {}

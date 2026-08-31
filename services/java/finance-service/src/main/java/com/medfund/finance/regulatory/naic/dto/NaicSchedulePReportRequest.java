package com.medfund.finance.regulatory.naic.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * Request body for {@code POST /api/v1/reports/regulatory/naic/schedule-p/submit}.
 *
 * <p>{@link #periodStart} + {@link #periodEnd} identify the reporting
 * year — NAIC Schedule P is annual (calendar-year) but the server does
 * not force alignment so mid-year reconciliation dry-runs work; UI
 * presents an annual picker.
 *
 * <p>{@link #reportingCurrency} is ignored for NAIC Schedule P — the
 * currency is fixed at USD by
 * {@link com.medfund.shared.report.regulatory.RegulatoryReportCurrency}
 * and a non-blank override is rejected with 422 upstream by the shape
 * service. The field is accepted (rather than removed) so the DTO shape
 * stays parallel to Phase 10 / 11.
 *
 * <p>{@link #submit} = {@code true} archives the composed XLSX into
 * {@code regulatory_submission} (with MFA step-up on the JWT). Default
 * is {@code false} — dry-run export for compliance review.
 */
public record NaicSchedulePReportRequest(
        @NotNull LocalDate periodStart,
        @NotNull LocalDate periodEnd,
        @JsonProperty("reportingCurrency") String reportingCurrency,
        @JsonProperty("submit") boolean submit,
        @JsonProperty("attestationNote") String attestationNote) {}

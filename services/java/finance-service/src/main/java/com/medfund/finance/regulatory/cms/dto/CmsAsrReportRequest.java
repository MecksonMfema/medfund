package com.medfund.finance.regulatory.cms.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * Request body for {@code POST /api/v1/reports/regulatory/cms/asr/submit}.
 *
 * <p>{@link #periodStart} + {@link #periodEnd} identify the reporting
 * year — the CMS ASR is nominally annual (12-month scheme year) but the
 * server does not force alignment so a mid-year dry-run for reconciliation
 * is possible; UI presents an annual picker.
 *
 * <p>{@link #reportingCurrency} is ignored for CMS ASR — the currency is
 * fixed at ZAR by {@link com.medfund.shared.report.regulatory.RegulatoryReportCurrency}
 * and a non-blank override is rejected with 422 upstream by the shape
 * service. The field is accepted (rather than removed) so the DTO shape
 * stays parallel to Phase 10's {@code IpecReportRequest}.
 *
 * <p>{@link #submit} = {@code true} archives the composed XLSX into
 * {@code regulatory_submission} (with MFA step-up on the JWT). Default
 * is {@code false} — dry-run export for compliance review.
 */
public record CmsAsrReportRequest(
        @NotNull LocalDate periodStart,
        @NotNull LocalDate periodEnd,
        @JsonProperty("reportingCurrency") String reportingCurrency,
        @JsonProperty("submit") boolean submit,
        @JsonProperty("attestationNote") String attestationNote) {}

package com.medfund.finance.regulatory.ipec.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * Request body for {@code POST /api/v1/reports/regulatory/ipec/quarterly-return/submit}.
 *
 * <p>{@link #periodStart} + {@link #periodEnd} identify the quarter — the
 * server does not force alignment (a tenant may run a partial-quarter
 * dry-run); UI presents a strict quarter picker but off-cycle exports are
 * intentionally allowed for reconciliation runs.
 *
 * <p>{@link #reportingCurrency} is ignored for IPEC — the currency is
 * fixed at ZWL by {@link com.medfund.shared.report.regulatory.RegulatoryReportCurrency}
 * and a non-blank override is rejected with 422 upstream by the
 * shape service. The field is accepted (rather than removed) so the DTO
 * shape stays parallel to Phase 15's {@code Ifrs17ReportRequest}.
 *
 * <p>{@link #submit} = {@code true} archives the composed XLSX into
 * {@code regulatory_submission} (with MFA step-up on the JWT). Default
 * is {@code false} — dry-run export for compliance review.
 */
public record IpecReportRequest(
        @NotNull LocalDate periodStart,
        @NotNull LocalDate periodEnd,
        @JsonProperty("reportingCurrency") String reportingCurrency,
        @JsonProperty("submit") boolean submit,
        @JsonProperty("attestationNote") String attestationNote) {}

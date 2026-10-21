package com.medfund.finance.regulatory.tax.vat.dto;

import java.util.UUID;

/**
 * Inline response for the VAT Return submit endpoint. Callers poll the
 * shared {@code /api/v1/reports/jobs/{jobId}} endpoint for the terminal
 * state and download the XLSX via
 * {@code /api/v1/reports/regulatory/tax/vat-return/jobs/{jobId}/xlsx}
 * once the status flips to {@code completed}.
 *
 * <p>{@link #deduplicated} is {@code true} when an in-flight identical job
 * was reused (same tenant + params_hash) — the frontend skips its
 * "submitting…" spinner and jumps straight into polling.
 */
public record VatReturnJobSubmissionResponse(UUID jobId, String status, boolean deduplicated) {}

package com.medfund.finance.regulatory.tax.wht.dto;

import java.util.UUID;

/**
 * Inline response for the WHT Return submit endpoint. Callers poll the
 * shared {@code /api/v1/reports/jobs/{jobId}} endpoint for the terminal
 * state and download the XLSX via
 * {@code /api/v1/reports/regulatory/tax/withheld-return/jobs/{jobId}/xlsx}.
 */
public record TaxWithheldReturnJobSubmissionResponse(UUID jobId, String status, boolean deduplicated) {}

package com.medfund.contributions.dto;

import java.util.List;

/**
 * One-shot render envelope for file-service.
 *
 * <p>Bundles the three pieces the statement page fetches separately
 * ({@link InvoiceResponse}, {@link StatementResponse},
 * per-invoice {@link InvoiceContributionRow}) into a single response so
 * the PDF renderer stays a single round-trip. The wire shape is
 * exercised by {@code file-service/internal/contributions.Client}.
 */
public record InvoiceRenderPayload(
        InvoiceResponse invoice,
        StatementResponse statement,
        List<InvoiceContributionRow> contributions) {}

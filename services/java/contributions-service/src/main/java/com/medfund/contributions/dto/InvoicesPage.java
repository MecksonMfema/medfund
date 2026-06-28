package com.medfund.contributions.dto;

import java.util.List;

/**
 * Page envelope for {@link InvoiceListRow}. Page-based pagination to
 * match the existing SchemesService convention.
 */
public record InvoicesPage(
        List<InvoiceListRow> content,
        long total,
        int page,
        int size,
        int totalPages
) {}

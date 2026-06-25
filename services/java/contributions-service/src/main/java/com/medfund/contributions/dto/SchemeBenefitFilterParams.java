package com.medfund.contributions.dto;

import java.util.UUID;

/**
 * Filter + sort + pagination inputs for the operational scheme-benefits list.
 *
 * <p>{@code sortKey} accepts only the whitelisted camelCase keys handled by
 * {@link com.medfund.contributions.repository.SchemeBenefitQueryRepository} —
 * any other value silently falls back to {@code name ASC} so the UI cannot
 * trigger SQL injection by passing an arbitrary column name.
 */
public record SchemeBenefitFilterParams(
        UUID schemeId,
        String q,
        String status,
        String benefitType,
        String sortKey,
        String sortDirection,
        int page,
        int size
) {}

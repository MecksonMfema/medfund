package com.medfund.contributions.dto;

/**
 * Filter + sort + pagination inputs for the operational schemes list.
 * {@code sortKey} accepts only the whitelisted camelCase keys handled by
 * {@link com.medfund.contributions.repository.SchemeQueryRepository} — any
 * other value silently falls back to {@code name ASC} so the UI cannot
 * trigger SQL injection by passing an arbitrary column name.
 */
public record SchemeFilterParams(
        String q,
        String status,
        String insuranceLine,
        String schemeType,
        String sortKey,
        String sortDirection,
        int page,
        int size
) {}

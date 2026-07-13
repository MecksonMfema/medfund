package com.medfund.claims.dto;

import java.util.List;

/**
 * Envelope for server-side paginated list endpoints. Mirrors the shape
 * used by {@code contributions-service.PageResponse} so the Angular
 * {@code <app-data-table>} component can consume claims-service pages
 * without a second envelope adapter.
 */
public record PageResponse<T>(
        List<T> content,
        long total,
        int page,
        int size,
        int totalPages
) {
    public static <T> PageResponse<T> of(List<T> content, long total, int page, int size) {
        int totalPages = size == 0 ? 0 : (int) Math.ceil((double) total / size);
        return new PageResponse<>(content, total, page, size, Math.max(totalPages, 1));
    }
}

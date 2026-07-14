package com.medfund.user.dto;

import java.util.List;

/**
 * Envelope for server-side paginated list endpoints. Mirrors the shape
 * used by {@code claims-service.PageResponse},
 * {@code contributions-service.PageResponse} and
 * {@code finance-service.PageResponse} so the Angular {@code <app-data-table>}
 * component can consume user-service pages without a second envelope
 * adapter.
 *
 * <p>Distinct from {@link CursorPage} — that is cursor-based and used by
 * heavy-scroll endpoints (members, staff-users). This offset variant is
 * used for admin list pages that need page/size + sort controls.
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

package com.medfund.contributions.dto;

import java.util.List;

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

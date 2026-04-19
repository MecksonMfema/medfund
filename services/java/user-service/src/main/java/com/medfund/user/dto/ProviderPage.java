package com.medfund.user.dto;

import java.util.List;

public record ProviderPage(
        List<ProviderResponse> content,
        long totalCount,
        int totalPages,
        int page,
        int size
) {
    public static ProviderPage of(List<ProviderResponse> content, long totalCount, int page, int size) {
        int totalPages = size > 0 ? (int) Math.ceil((double) totalCount / size) : 1;
        return new ProviderPage(content, totalCount, Math.max(1, totalPages), page, size);
    }
}

package com.medfund.claims.dto;

public record TariffCategoryFilterParams(
        Boolean activeOnly,
        Boolean capOnly,
        String q,
        String sortKey,
        String sortDirection,
        int page,
        int size
) {
}

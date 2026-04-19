package com.medfund.tenancy.dto;

import java.util.List;

public record TenantPage(
        List<TenantResponse> content,
        long totalCount,
        int totalPages,
        int page,
        int size
) {}

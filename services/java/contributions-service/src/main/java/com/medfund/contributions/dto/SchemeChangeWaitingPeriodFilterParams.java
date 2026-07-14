package com.medfund.contributions.dto;

public record SchemeChangeWaitingPeriodFilterParams(
        String changeType,
        String benefitType,
        Boolean activeOnly,
        String q,
        String sortKey,
        String sortDirection,
        int page,
        int size
) {
}

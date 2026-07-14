package com.medfund.contributions.dto;

import java.util.UUID;

public record WaitingPeriodFilterParams(
        UUID schemeId,
        String conditionType,
        String q,
        String sortKey,
        String sortDirection,
        int page,
        int size
) {
}

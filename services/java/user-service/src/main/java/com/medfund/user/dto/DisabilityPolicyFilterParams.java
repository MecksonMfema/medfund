package com.medfund.user.dto;

import java.util.UUID;

public record DisabilityPolicyFilterParams(
        String status,
        UUID schemeId,
        String q,
        String sortKey,
        String sortDirection,
        int page,
        int size
) {
}

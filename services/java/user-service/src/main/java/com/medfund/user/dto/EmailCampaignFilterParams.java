package com.medfund.user.dto;

import java.util.UUID;

public record EmailCampaignFilterParams(
        String status,
        UUID senderId,
        String q,
        String sortKey,
        String sortDirection,
        int page,
        int size
) {
}

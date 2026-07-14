package com.medfund.user.dto;

public record EmailSenderFilterParams(
        String status,
        String q,
        String sortKey,
        String sortDirection,
        int page,
        int size
) {
}

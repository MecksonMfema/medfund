package com.medfund.claims.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Row shape for the paginated tariff-codes list. Carries the joined
 * {@code categoryLabel} so the Angular table renders directly without
 * a client-side category-catalogue lookup — the AHFOZ schedule has
 * ~5k codes, and a per-row {@code Array.find} across the categories
 * catalogue is what made the previous page feel sluggish.
 */
public record TariffCodeRow(
        UUID id,
        UUID scheduleId,
        String code,
        String description,
        UUID categoryId,
        String categoryLabel,
        BigDecimal unitPrice,
        String currencyCode,
        Boolean requiresPreAuth
) {
}

package com.medfund.claims.siu.dto;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Top-N provider row for the {@code /reports/fraud/top-providers}
 * endpoint (§B Phase 11). Ranked descending by {@code savingsComposite}
 * (fx-converted to the reporting currency); {@code savingsNative} keeps
 * the per-currency breakdown for the chip strip on the row.
 *
 * <p>{@code providerName} + {@code providerCode} are looked up client-side
 * from the entity-picker cache — the backend echoes {@code providerId}
 * only to keep the query tenant-scoped tight.
 */
public record ProviderTopNRow(
        UUID providerId,
        String providerName,
        String providerCode,
        long confirmedCases,
        Map<String, BigDecimal> savingsNative,
        BigDecimal savingsComposite
) {
}

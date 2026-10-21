package com.medfund.finance.reinsurance.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One utilization row per {@code (treaty, layer, ceded_currency)} since
 * treaty inception. Non-proportional treaties have per-layer rows; QS/SS
 * treaties (no layers) collapse to a single logical layer with
 * {@code layerId = null}.
 *
 * <p>Utilization ratio is computed client-side from {@code totalCededNative}
 * and either {@code layerLimit} (per-layer) or {@code aggregateLimit}
 * (whole-treaty) — kept out of the SQL because both denominators can be
 * NULL and the report renders "n/a" in that case.
 */
public record TreatyUtilizationRow(
        UUID treatyId,
        String treatyRef,
        String treatyType,
        BigDecimal aggregateLimit,
        String aggregateLimitCurrency,
        LocalDate inceptionDate,
        LocalDate expiryDate,
        UUID layerId,
        Integer layerOrder,
        BigDecimal retention,
        BigDecimal layerLimit,
        String layerCurrency,
        String cededCurrency,
        BigDecimal totalCededNative,
        Long cessionCount
) {}

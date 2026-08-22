package com.medfund.finance.reinsurance.dto;

import com.medfund.finance.reinsurance.entity.TreatyLayer;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record TreatyLayerResponse(
        UUID id,
        UUID treatyId,
        Integer layerOrder,
        BigDecimal retention,
        BigDecimal layerLimit,
        String layerCurrency,
        BigDecimal rate,
        Integer reinstatementCount,
        OffsetDateTime createdAt
) {
    public static TreatyLayerResponse from(TreatyLayer l) {
        return new TreatyLayerResponse(
                l.getId(), l.getTreatyId(), l.getLayerOrder(),
                l.getRetention(), l.getLayerLimit(), l.getLayerCurrency(),
                l.getRate(), l.getReinstatementCount(), l.getCreatedAt());
    }
}

package com.medfund.finance.reinsurance.dto;

import com.medfund.finance.reinsurance.entity.Cession;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Read-side projection of {@link Cession}. Used by the facultative
 * controller (create-draft response + queue rows) and any future
 * cession-detail surface.
 */
public record CessionResponse(
        UUID id,
        UUID treatyId,
        UUID treatyLayerId,
        String cessionType,
        String source,
        String status,
        UUID sourceEventId,
        String sourceEventType,
        BigDecimal cededAmount,
        String currencyCode,
        BigDecimal basisAmount,
        OffsetDateTime occurredAt,
        String voidedReason,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    public static CessionResponse from(Cession c) {
        return new CessionResponse(
                c.getId(),
                c.getTreatyId(),
                c.getTreatyLayerId(),
                c.getCessionType(),
                c.getSource(),
                c.getStatus(),
                c.getSourceEventId(),
                c.getSourceEventType(),
                c.getCededAmount(),
                c.getCurrencyCode(),
                c.getBasisAmount(),
                c.getOccurredAt(),
                c.getVoidedReason(),
                c.getCreatedAt(),
                c.getUpdatedAt()
        );
    }
}

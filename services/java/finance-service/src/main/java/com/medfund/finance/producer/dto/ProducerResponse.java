package com.medfund.finance.producer.dto;

import com.medfund.finance.producer.entity.Producer;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record ProducerResponse(
        UUID id,
        String producerCode,
        String name,
        String contactEmail,
        String contactPhone,
        String jurisdictionCode,
        String homeCurrency,
        UUID parentProducerId,
        BigDecimal whtPctOverride,
        String bankingDetailsJson,
        Boolean active,
        OffsetDateTime activatedAt,
        OffsetDateTime terminatedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static ProducerResponse from(Producer p) {
        return new ProducerResponse(
                p.getId(), p.getProducerCode(), p.getName(),
                p.getContactEmail(), p.getContactPhone(),
                p.getJurisdictionCode(), p.getHomeCurrency(),
                p.getParentProducerId(), p.getWhtPctOverride(),
                p.getBankingDetailsJson(), p.getActive(),
                p.getActivatedAt(), p.getTerminatedAt(),
                p.getCreatedAt(), p.getUpdatedAt());
    }
}

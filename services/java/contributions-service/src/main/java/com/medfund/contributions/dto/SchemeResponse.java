package com.medfund.contributions.dto;

import com.medfund.contributions.entity.Scheme;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record SchemeResponse(
        UUID id,
        String name,
        String description,
        String schemeType,
        String status,
        LocalDate effectiveDate,
        LocalDate endDate,
        String currencyCode,
        Instant createdAt,
        Instant updatedAt,
        UUID createdBy,
        UUID updatedBy
) {
    public static SchemeResponse from(Scheme scheme) {
        return new SchemeResponse(
                scheme.getId(),
                scheme.getName(),
                scheme.getDescription(),
                scheme.getSchemeType(),
                scheme.getStatus(),
                scheme.getEffectiveDate(),
                scheme.getEndDate(),
                scheme.getCurrencyCode(),
                scheme.getCreatedAt(),
                scheme.getUpdatedAt(),
                scheme.getCreatedBy(),
                scheme.getUpdatedBy()
        );
    }
}

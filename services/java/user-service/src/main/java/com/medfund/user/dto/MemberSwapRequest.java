package com.medfund.user.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.UUID;

public record MemberSwapRequest(
        @NotNull UUID dependantId,
        @NotNull LocalDate effectiveDate,
        String reason
) {
    public LocalDate effectiveDateOrDefault() {
        return effectiveDate != null ? effectiveDate.withDayOfMonth(1)
                : LocalDate.now().withDayOfMonth(1);
    }
}

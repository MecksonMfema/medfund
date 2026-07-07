package com.medfund.claims.dto;

import com.medfund.shared.validation.EndOfMonth;
import com.medfund.shared.validation.FirstOfMonth;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record CreateTariffScheduleRequest(
        @NotBlank String name,
        @NotNull @FirstOfMonth LocalDate effectiveDate,
        @EndOfMonth LocalDate endDate,
        String source
) {
}

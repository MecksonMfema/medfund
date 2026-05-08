package com.medfund.contributions.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record UpsertDunningConfigRequest(
        @NotNull @Min(0) Integer graceDays,
        @NotNull @Min(0) Integer suspensionDays,
        @NotNull @Min(0) Integer writeOffDays,
        Boolean autoSuspend,
        Boolean autoWriteOff
) {}

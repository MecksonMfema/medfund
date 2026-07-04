package com.medfund.contributions.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record UpsertDunningConfigRequest(
        @NotNull @Min(0) Integer graceDays,
        @NotNull @Min(0) Integer suspensionDays,
        /** Renamed from writeOffDays in V042. Same semantics: days
         *  overdue at which auto_write_off flips the row to deactivated. */
        @NotNull @Min(0) Integer deactivationDays,
        Boolean autoSuspend,
        Boolean autoWriteOff,
        /** V044 — arrears-reminder cadence knobs. All optional; nulls
         *  keep the existing values. */
        Boolean autoRemind,
        @Min(0) Integer reminderLeadDays,
        @Min(1) Integer reminderIntervalDays,
        Boolean reminderContinuePastSuspension
) {}

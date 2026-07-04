package com.medfund.contributions.dto;

import com.medfund.contributions.entity.DunningConfig;

import java.time.Instant;

public record DunningConfigResponse(
        Integer graceDays,
        Integer suspensionDays,
        Integer deactivationDays,
        Boolean autoSuspend,
        Boolean autoWriteOff,
        Boolean autoRemind,
        Integer reminderLeadDays,
        Integer reminderIntervalDays,
        Boolean reminderContinuePastSuspension,
        Instant updatedAt
) {
    public static DunningConfigResponse from(DunningConfig d) {
        return new DunningConfigResponse(
                d.getGraceDays(), d.getSuspensionDays(), d.getDeactivationDays(),
                d.getAutoSuspend(), d.getAutoWriteOff(),
                d.getAutoRemind(),
                d.getReminderLeadDays(),
                d.getReminderIntervalDays(),
                d.getReminderContinuePastSuspension(),
                d.getUpdatedAt());
    }
}

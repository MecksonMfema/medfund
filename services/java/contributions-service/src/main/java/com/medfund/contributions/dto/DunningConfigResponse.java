package com.medfund.contributions.dto;

import com.medfund.contributions.entity.DunningConfig;

import java.time.Instant;

public record DunningConfigResponse(
        Integer graceDays,
        Integer suspensionDays,
        Integer writeOffDays,
        Boolean autoSuspend,
        Boolean autoWriteOff,
        Instant updatedAt
) {
    public static DunningConfigResponse from(DunningConfig d) {
        return new DunningConfigResponse(d.getGraceDays(), d.getSuspensionDays(), d.getWriteOffDays(),
                d.getAutoSuspend(), d.getAutoWriteOff(), d.getUpdatedAt());
    }
}

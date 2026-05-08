package com.medfund.contributions.dto;

import com.medfund.contributions.entity.BillingCycleConfig;

import java.time.Instant;

public record BillingCycleConfigResponse(
        String frequency,
        Short dayOfMonth,
        Boolean autoGenerate,
        Short commitCooldownHours,
        Instant lastCommittedAt,
        Instant updatedAt
) {
    public static BillingCycleConfigResponse from(BillingCycleConfig c) {
        return new BillingCycleConfigResponse(c.getFrequency(), c.getDayOfMonth(),
                c.getAutoGenerate(), c.getCommitCooldownHours(),
                c.getLastCommittedAt(), c.getUpdatedAt());
    }
}

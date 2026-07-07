package com.medfund.user.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Request body for POST /api/members/{id}/group-changes.
 * effectiveDate must be the 1st of a month; the service snaps
 * caller-supplied mid-month picks to the 1st defensively even
 * though the frontend does the same (belt + braces).
 */
public record GroupChangeRequest(
        @NotNull UUID targetGroupId,
        @NotNull LocalDate effectiveDate,
        String reason
) {
    public LocalDate effectiveDateOrDefault() {
        return effectiveDate != null ? effectiveDate.withDayOfMonth(1)
                : LocalDate.now().withDayOfMonth(1);
    }
}

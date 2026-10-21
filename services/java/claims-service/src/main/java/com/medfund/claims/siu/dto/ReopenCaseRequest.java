package com.medfund.claims.siu.dto;

/**
 * Payload for {@code POST /api/v1/siu/cases/{caseId}/reopen}.
 * Transitions any CLOSED_* status back to UNDER_REVIEW via a
 * transient REOPENED note. Requires {@code claims:siu:reopen}.
 */
public record ReopenCaseRequest(
        String reopenReason
) {
}

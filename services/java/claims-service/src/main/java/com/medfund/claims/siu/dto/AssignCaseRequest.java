package com.medfund.claims.siu.dto;

import java.util.UUID;

/**
 * Payload for {@code POST /api/v1/siu/cases/{caseId}/assign}.
 * Transitions OPEN → ASSIGNED and sets {@code siu_case.assigned_to}.
 * Requires {@code claims:siu:assign}.
 */
public record AssignCaseRequest(
        UUID assigneeId
) {
}

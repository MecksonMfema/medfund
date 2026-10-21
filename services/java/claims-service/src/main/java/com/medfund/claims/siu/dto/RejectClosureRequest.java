package com.medfund.claims.siu.dto;

/**
 * Payload for {@code POST /api/v1/siu/cases/{caseId}/reject-closure}.
 * Transitions PENDING_APPROVAL → UNDER_REVIEW; supervisor sends the
 * proposed closure back to the investigator with a rejection note.
 */
public record RejectClosureRequest(
        String rejectionNote
) {
}

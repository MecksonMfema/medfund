package com.medfund.claims.siu.dto;

import com.medfund.claims.siu.entity.SiuCase;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * List-row shape for the SIU workqueue (Phase 19 §A Phase 6). Trimmed to
 * the columns the queue page actually renders — the full case surface
 * (flags + notes) lives on {@link SiuCaseResponse}.
 */
public record SiuCaseSummaryResponse(
        UUID id,
        String caseNumber,
        String status,
        String priority,
        UUID assignedTo,
        OffsetDateTime openedAt,
        long flagCount
) {
    public static SiuCaseSummaryResponse from(SiuCase kase, long flagCount) {
        return new SiuCaseSummaryResponse(
                kase.getId(),
                kase.getCaseNumber(),
                kase.getStatus(),
                kase.getPriority(),
                kase.getAssignedTo(),
                kase.getOpenedAt(),
                flagCount
        );
    }
}

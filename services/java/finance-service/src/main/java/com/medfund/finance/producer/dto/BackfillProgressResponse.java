package com.medfund.finance.producer.dto;

import com.medfund.finance.producer.service.ProducerBackfillProgressService;

import java.time.OffsetDateTime;

/**
 * Snapshot of the current backfill progress for a tenant, returned by
 * {@code GET /api/v1/producers/backfill/progress}. Every counter is
 * best-effort — the underlying state lives per-node so a poll hitting a
 * different node than the one that ran the job returns the {@code idle}
 * shape (all zeros, {@code startedAt = null}).
 */
public record BackfillProgressResponse(
        OffsetDateTime startedAt,
        OffsetDateTime completedAt,
        int processed,
        int skipped,
        int autoAccepted,
        int pending,
        int failed,
        boolean running,
        String errorMessage
) {

    public static BackfillProgressResponse idle() {
        return new BackfillProgressResponse(null, null, 0, 0, 0, 0, 0, false, null);
    }

    public static BackfillProgressResponse from(ProducerBackfillProgressService.Progress p) {
        return new BackfillProgressResponse(
                p.getStartedAt(),
                p.getCompletedAt(),
                p.getProcessed(),
                p.getSkipped(),
                p.getAutoAccepted(),
                p.getPending(),
                p.getFailed(),
                p.isRunning(),
                p.getErrorMessage());
    }
}

package com.medfund.finance.reinsurance.service;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory backfill-progress tracker keyed by treaty id. The activation
 * backfill is fire-and-forget from the operator's point of view, so we
 * expose a small polling surface for the UI (progress bar on the treaty
 * detail page) via {@code GET /api/v1/reinsurance/treaties/{id}/backfill-progress}.
 *
 * <p>State lives only in-process — a restart drops progress. That's
 * acceptable because the underlying writes are idempotent via
 * {@code ux_cession_source_event}; the operator re-triggering the
 * backfill after a restart writes zero duplicates. If we ever need
 * durable progress (multi-node deployments where the poll can hit a
 * node that didn't run the job), promote this to a table.
 */
@Slf4j
@Service
public class BackfillProgressService {

    private final ConcurrentHashMap<UUID, Progress> byTreaty = new ConcurrentHashMap<>();

    public Progress start(UUID treatyId) {
        Progress p = new Progress(OffsetDateTime.now());
        byTreaty.put(treatyId, p);
        log.info("Backfill started for treaty {}", treatyId);
        return p;
    }

    public Optional<Progress> get(UUID treatyId) {
        return Optional.ofNullable(byTreaty.get(treatyId));
    }

    public void incrementProcessed(UUID treatyId) {
        Progress p = byTreaty.get(treatyId);
        if (p != null) p.processed.incrementAndGet();
    }

    public void incrementFailed(UUID treatyId) {
        Progress p = byTreaty.get(treatyId);
        if (p != null) p.failed.incrementAndGet();
    }

    public void complete(UUID treatyId) {
        Progress p = byTreaty.get(treatyId);
        if (p != null) {
            p.completedAt = OffsetDateTime.now();
            log.info("Backfill complete for treaty {}: processed={}, failed={}",
                    treatyId, p.processed.get(), p.failed.get());
        }
    }

    public void fail(UUID treatyId, String reason) {
        Progress p = byTreaty.get(treatyId);
        if (p != null) {
            p.completedAt = OffsetDateTime.now();
            p.errorMessage = reason;
            log.error("Backfill failed for treaty {}: {}", treatyId, reason);
        }
    }

    @Getter
    public static class Progress {
        private final OffsetDateTime startedAt;
        private final AtomicInteger processed = new AtomicInteger();
        private final AtomicInteger failed = new AtomicInteger();
        private volatile OffsetDateTime completedAt;
        private volatile String errorMessage;

        Progress(OffsetDateTime startedAt) {
            this.startedAt = startedAt;
        }

        public int getProcessed() { return processed.get(); }
        public int getFailed()    { return failed.get(); }
        public boolean isRunning() { return completedAt == null; }
    }
}

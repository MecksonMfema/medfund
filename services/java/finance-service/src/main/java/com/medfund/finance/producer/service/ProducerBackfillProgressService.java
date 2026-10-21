package com.medfund.finance.producer.service;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory backfill-progress tracker keyed by tenantId. Mirrors
 * {@link com.medfund.finance.reinsurance.service.BackfillProgressService}
 * shape but tracks the richer set of counters the producer backfill needs
 * (processed / skipped / autoAccepted / pending / failed) — the treaty
 * backfill is a one-per-tenant operation so only the most recent run is
 * exposed via the polling surface.
 *
 * <p>State is per-node only. A restart drops it; that's acceptable because
 * {@code ux_pbc_treaty_candidate} makes a rerun idempotent.
 */
@Slf4j
@Service
public class ProducerBackfillProgressService {

    private final ConcurrentHashMap<UUID, Progress> byTenant = new ConcurrentHashMap<>();

    public Progress start(UUID tenantId) {
        Progress p = new Progress(OffsetDateTime.now());
        byTenant.put(tenantId, p);
        log.info("Producer backfill started for tenant {}", tenantId);
        return p;
    }

    public Optional<Progress> get(UUID tenantId) {
        return Optional.ofNullable(byTenant.get(tenantId));
    }

    public void recordProcessed(UUID tenantId) {
        Progress p = byTenant.get(tenantId);
        if (p != null) p.processed.incrementAndGet();
    }

    public void recordAutoAccepted(UUID tenantId) {
        Progress p = byTenant.get(tenantId);
        if (p != null) p.autoAccepted.incrementAndGet();
    }

    public void recordPending(UUID tenantId) {
        Progress p = byTenant.get(tenantId);
        if (p != null) p.pending.incrementAndGet();
    }

    public void recordSkip(UUID tenantId, UUID treatyId) {
        Progress p = byTenant.get(tenantId);
        if (p != null) {
            p.skipped.incrementAndGet();
            log.debug("Producer backfill skipped treaty {} for tenant {} (no plausible match)",
                    treatyId, tenantId);
        }
    }

    public void recordFailed(UUID tenantId) {
        Progress p = byTenant.get(tenantId);
        if (p != null) p.failed.incrementAndGet();
    }

    public void complete(UUID tenantId) {
        Progress p = byTenant.get(tenantId);
        if (p != null) {
            p.completedAt = OffsetDateTime.now();
            log.info("Producer backfill complete for tenant {}: processed={}, autoAccepted={}, "
                    + "pending={}, skipped={}, failed={}",
                    tenantId, p.processed.get(), p.autoAccepted.get(),
                    p.pending.get(), p.skipped.get(), p.failed.get());
        }
    }

    public void fail(UUID tenantId, String reason) {
        Progress p = byTenant.get(tenantId);
        if (p != null) {
            p.completedAt = OffsetDateTime.now();
            p.errorMessage = reason;
            log.error("Producer backfill failed for tenant {}: {}", tenantId, reason);
        }
    }

    @Getter
    public static class Progress {
        private final OffsetDateTime startedAt;
        private final AtomicInteger processed = new AtomicInteger();
        private final AtomicInteger skipped = new AtomicInteger();
        private final AtomicInteger autoAccepted = new AtomicInteger();
        private final AtomicInteger pending = new AtomicInteger();
        private final AtomicInteger failed = new AtomicInteger();
        private volatile OffsetDateTime completedAt;
        private volatile String errorMessage;

        Progress(OffsetDateTime startedAt) {
            this.startedAt = startedAt;
        }

        public int getProcessed()    { return processed.get(); }
        public int getSkipped()      { return skipped.get(); }
        public int getAutoAccepted() { return autoAccepted.get(); }
        public int getPending()      { return pending.get(); }
        public int getFailed()       { return failed.get(); }
        public boolean isRunning()   { return completedAt == null; }
    }
}

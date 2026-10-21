package com.medfund.finance.reinsurance.service;

import com.medfund.finance.client.ClaimsClient;
import com.medfund.finance.reinsurance.dto.ClaimAdjudicatedEvent;
import com.medfund.finance.reinsurance.entity.Treaty;
import com.medfund.finance.reinsurance.entity.TreatyApplicableLine;
import com.medfund.finance.reinsurance.repository.TreatyApplicableLineRepository;
import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.context.Context;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Retroactive backfill kicked off by
 * {@link TreatyService#activate(UUID, String, String)} once a treaty
 * transitions DRAFT → ACTIVE. Scans ADJUDICATED claims across every
 * applicable insurance line whose submission date is on or after
 * {@code treaty.inceptionDate} and re-fires the reinsurance rules
 * engine against each — writing one Cession + one Recovery per rule
 * hit, just like the live consumer path.
 *
 * <p>Runs off the caller's transaction: the caller subscribes on
 * {@link Schedulers#parallel()} inside a preserved tenant context, so
 * treaty activation returns immediately and the operator polls
 * {@link BackfillProgressService} for progress.
 *
 * <p>Idempotency: the underlying
 * {@link com.medfund.finance.reinsurance.service.CessionService#processAdjudicatedClaim}
 * enforces uniqueness via {@code ux_cession_source_event} — a re-run of
 * the backfill after a partial failure writes zero duplicates.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TreatyActivationBackfillJob {

    private final TreatyApplicableLineRepository applicableLineRepository;
    private final ClaimsClient claimsClient;
    private final CessionService cessionService;
    private final BackfillProgressService progressService;

    /**
     * Fire-and-forget kick-off. Returns immediately; the caller does
     * not await completion. Errors are logged and surface via
     * {@link BackfillProgressService#get(UUID)}'s {@code errorMessage}
     * field for polling clients.
     */
    public void kickOff(Treaty treaty, String tenantId, String actorId, String actorEmail) {
        if (treaty == null || treaty.getId() == null) return;
        progressService.start(treaty.getId());
        backfill(treaty, actorId, actorEmail)
                .subscribeOn(Schedulers.parallel())
                .contextWrite(Context.of(TenantContext.KEY, tenantId))
                .subscribe(
                        v -> {},
                        err -> {
                            log.error("Treaty {} backfill failed: ", treaty.getId(), err);
                            progressService.fail(treaty.getId(),
                                    err.getMessage() != null ? err.getMessage() : err.getClass().getSimpleName());
                        },
                        () -> progressService.complete(treaty.getId()));
    }

    /**
     * Package-private for tests — synchronous, tenant-context-carrying
     * variant. {@link #kickOff(Treaty, String, String, String)} is what
     * production callers use.
     */
    Mono<Void> backfill(Treaty treaty, String actorId, String actorEmail) {
        return applicableLineRepository.findByTreatyId(treaty.getId())
                .map(TreatyApplicableLine::getInsuranceLine)
                .flatMap(line -> backfillLine(treaty, line, actorId, actorEmail))
                .then();
    }

    private Mono<Void> backfillLine(Treaty treaty, String insuranceLine,
                                    String actorId, String actorEmail) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            return claimsClient.streamAdjudicatedClaimsForBackfill(
                            insuranceLine, treaty.getInceptionDate(), 100)
                    .flatMap(row -> writeCession(treaty, insuranceLine, tenantId,
                                    row, actorId, actorEmail),
                            /* concurrency */ 4)
                    .then();
        });
    }

    private Mono<Void> writeCession(Treaty treaty, String insuranceLine, String tenantId,
                                    ClaimsClient.AdjudicatedClaimRow row,
                                    String actorId, String actorEmail) {
        ClaimAdjudicatedEvent event = new ClaimAdjudicatedEvent(
                row.claimId(),
                null,
                row.memberId(),
                row.providerId(),
                insuranceLine,
                row.currencyCode(),
                row.approvedAmount(),
                "APPROVED",
                null,
                row.adjudicatedAt() != null ? row.adjudicatedAt() : OffsetDateTime.now(),
                tenantId);
        String finalActorId = actorId != null ? actorId : AuditActor.systemActor()[0];
        String finalActorEmail = actorEmail != null ? actorEmail : AuditActor.systemActor()[1];
        return cessionService.processAdjudicatedClaim(event, finalActorId, finalActorEmail)
                .then()
                .doOnSuccess(v -> progressService.incrementProcessed(treaty.getId()))
                .onErrorResume(err -> {
                    log.warn("Backfill row failed for treaty={} claim={}: {}",
                            treaty.getId(), row.claimId(), err.getMessage());
                    progressService.incrementFailed(treaty.getId());
                    return Mono.empty();
                });
    }
}

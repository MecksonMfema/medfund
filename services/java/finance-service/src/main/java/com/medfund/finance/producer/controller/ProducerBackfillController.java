package com.medfund.finance.producer.controller;

import com.medfund.finance.producer.dto.BackfillCandidateResponse;
import com.medfund.finance.producer.dto.BackfillProgressResponse;
import com.medfund.finance.producer.service.ProducerBackfillJob;
import com.medfund.finance.producer.service.ProducerBackfillProgressService;
import com.medfund.finance.producer.service.ProducerBackfillReviewService;
import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.security.Permissions;
import com.medfund.shared.security.RequiresPermission;
import com.medfund.shared.tenant.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.context.Context;

import java.util.UUID;

/**
 * Tenant-admin surface for the {@code treaty.producer_ref → producer_id}
 * backfill (Phase 10). {@code POST /run} kicks off the job fire-and-forget
 * — the caller polls {@code GET /progress} for status. Reviewers accept or
 * reject low-confidence candidates one at a time.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/producers/backfill")
@RequiredArgsConstructor
@Tag(name = "Producer backfill",
     description = "Fuzzy-match treaty.producer_ref legacy free-text to producer.id, with tenant-admin review.")
@SecurityRequirement(name = "bearer-jwt")
public class ProducerBackfillController {

    private final ProducerBackfillJob backfillJob;
    private final ProducerBackfillReviewService reviewService;
    private final ProducerBackfillProgressService progressService;

    @PostMapping("/run")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @RequiresPermission(Permissions.PRODUCER_BACKFILL_REVIEW)
    @Operation(summary = "Kick off the treaty.producer_ref → producer_id backfill (fire-and-forget)",
            description = "Idempotent - reruns write zero duplicate candidate rows thanks to "
                        + "ux_pbc_treaty_candidate. Returns 202 immediately; poll /progress for status.")
    public Mono<Void> run(@AuthenticationPrincipal Jwt jwt) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            String actorId = AuditActor.id(jwt);
            String actorEmail = AuditActor.email(jwt);
            backfillJob.runBackfill(actorId, actorEmail)
                    .subscribeOn(Schedulers.boundedElastic())
                    .contextWrite(Context.of(TenantContext.KEY,
                            tenantId != null ? tenantId : ""))
                    .subscribe(
                            v -> {},
                            err -> log.error("Producer backfill failed for tenant {}: ", tenantId, err),
                            () -> log.debug("Producer backfill run complete for tenant {}", tenantId));
            return Mono.empty();
        });
    }

    @GetMapping("/progress")
    @RequiresPermission(Permissions.PRODUCER_BACKFILL_REVIEW)
    @Operation(summary = "Current backfill progress for this tenant",
            description = "In-memory only; a restart drops progress. Returns idle-shape zeros if no run yet.")
    public Mono<BackfillProgressResponse> progress() {
        return Mono.deferContextual(ctx -> {
            UUID tenantId = tenantUuidFrom(TenantContext.get(ctx));
            return Mono.just(progressService.get(tenantId)
                    .map(BackfillProgressResponse::from)
                    .orElseGet(BackfillProgressResponse::idle));
        });
    }

    @GetMapping("/candidates")
    @RequiresPermission(Permissions.PRODUCER_BACKFILL_REVIEW)
    @Operation(summary = "List PENDING backfill candidates for review",
            description = "Paged, ordered by confidence DESC. Enriched with treaty ref + producer name/code so "
                        + "the UI can render source → suggestion rows without a join.")
    public Flux<BackfillCandidateResponse> candidates(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "50") int size) {
        return reviewService.listPending(page, size);
    }

    @GetMapping("/candidates/pending-count")
    @RequiresPermission(Permissions.PRODUCER_BACKFILL_REVIEW)
    @Operation(summary = "Count of PENDING backfill candidates (drives sidebar badge)")
    public Mono<Long> pendingCount() {
        return reviewService.countPending();
    }

    @PutMapping("/candidates/{id}/accept")
    @RequiresPermission(Permissions.PRODUCER_BACKFILL_REVIEW)
    @Operation(summary = "Accept a candidate - sets treaty.producer_id + rejects sibling candidates")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Accepted; treaty.producer_id updated"),
            @ApiResponse(responseCode = "400", description = "Candidate not found"),
            @ApiResponse(responseCode = "409", description = "Candidate already resolved")
    })
    public Mono<Void> accept(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return reviewService.accept(id, AuditActor.id(jwt), AuditActor.email(jwt));
    }

    @PutMapping("/candidates/{id}/reject")
    @RequiresPermission(Permissions.PRODUCER_BACKFILL_REVIEW)
    @Operation(summary = "Reject a candidate - leaves the treaty untouched")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Rejected"),
            @ApiResponse(responseCode = "400", description = "Candidate not found"),
            @ApiResponse(responseCode = "409", description = "Candidate already resolved")
    })
    public Mono<Void> reject(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return reviewService.reject(id, AuditActor.id(jwt), AuditActor.email(jwt));
    }

    private static UUID tenantUuidFrom(String raw) {
        if (raw == null || raw.isBlank()) return new UUID(0L, 0L);
        try { return UUID.fromString(raw); } catch (IllegalArgumentException e) {
            return new UUID(0L, raw.hashCode());
        }
    }
}

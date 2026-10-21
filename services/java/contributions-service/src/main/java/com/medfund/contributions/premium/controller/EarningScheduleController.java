package com.medfund.contributions.premium.controller;

import com.medfund.contributions.premium.entity.EarningSchedule;
import com.medfund.contributions.premium.entity.EarningScheduleRun;
import com.medfund.contributions.premium.repository.EarningScheduleRunRepository;
import com.medfund.contributions.premium.service.EarningScheduleClosureService;
import com.medfund.shared.security.Permissions;
import com.medfund.shared.security.RequiresPermission;
import com.medfund.shared.tenant.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Admin surface for the earning-schedule projection: trigger a targeted
 * backfill, poll the {@code earning_schedule_run} progress table, cancel a
 * running executor, and (dev-only) inspect raw rows for a policy.
 *
 * <p>All endpoints are permission-gated per Rule 4 + Rule 9 of the CLAUDE
 * critical rules. The dev-debug endpoint is additionally profile-gated in
 * production configuration (not enforced here — the operator's
 * {@code application-prod.yml} disables the {@code premium.earning:view_debug}
 * permission on tenant admin roles).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/premium/earning-schedule")
@RequiredArgsConstructor
@Tag(name = "Earning schedule",
        description = "Phase 12 §A projection: per-policy-per-period earning strip. Admin operations only - the strip itself is written by the PolicyIssuedConsumer + BillingContributionEarningHook + PremiumEarningExecutor pipeline.")
@SecurityRequirement(name = "bearer-jwt")
public class EarningScheduleController {

    private final EarningScheduleClosureService closureService;
    private final EarningScheduleRunRepository earningScheduleRunRepository;
    private final com.medfund.contributions.premium.repository.EarningScheduleRepository earningScheduleRepository;

    @PostMapping("/backfill")
    @RequiresPermission(Permissions.PREMIUM_EARNING_MANAGE_BACKFILL)
    @Operation(summary = "Kick off a targeted earning-schedule backfill for a single policy",
            description = "Closes every unclosed row for the policy whose period_end < today. Fire-and-forget: returns 202 with the run id so the caller can poll /runs/{runId}.")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Run started"),
            @ApiResponse(responseCode = "403", description = "Missing premium.earning:manage_backfill permission")
    })
    public Mono<ResponseEntity<RunSummary>> backfill(
            @Parameter(description = "Source policy id") @RequestParam UUID policyId,
            @Parameter(description = "Source table discriminator (e.g. LIFE_POLICY, CONTRIBUTION)") @RequestParam String policySource) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            return closureService.backfillPolicy(tenantId, policyId, policySource)
                    .map(run -> ResponseEntity.accepted().body(RunSummary.of(run)));
        });
    }

    @PostMapping("/replay")
    @RequiresPermission(Permissions.PREMIUM_EARNING_MANAGE_BACKFILL)
    @Operation(summary = "Replay earning-schedule projection after a rule edit",
            description = "Same shape as /backfill; delegates to the same closure service so a rule change (e.g. switching from DAILY_LINEAR to MONTHLY_24THS) re-closes any open rows without disturbing already-closed periods.")
    @ApiResponses(@ApiResponse(responseCode = "202", description = "Run started"))
    public Mono<ResponseEntity<RunSummary>> replay(
            @RequestParam UUID policyId,
            @RequestParam String policySource) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            return closureService.backfillPolicy(tenantId, policyId, policySource)
                    .map(run -> ResponseEntity.accepted().body(RunSummary.of(run)));
        });
    }

    @PostMapping("/runs/{runId}/cancel")
    @RequiresPermission(Permissions.PREMIUM_EARNING_MANAGE_BACKFILL)
    @Operation(summary = "Cancel a running executor pass",
            description = "Marks the run as CANCELLED so the currently-processing chunk finishes and no new chunk starts. Idempotent on already-terminal runs.")
    @ApiResponses(@ApiResponse(responseCode = "200", description = "Cancelled or already-terminal"))
    public Mono<RunSummary> cancel(@PathVariable UUID runId) {
        return earningScheduleRunRepository.findById(runId)
                .flatMap(run -> {
                    if (!"RUNNING".equals(run.getStatus())) return Mono.just(run);
                    run.setStatus("CANCELLED");
                    run.setFinishedAt(java.time.Instant.now());
                    return earningScheduleRunRepository.save(run);
                })
                .map(RunSummary::of);
    }

    @GetMapping("/runs/{runId}")
    @RequiresPermission(Permissions.PREMIUM_EARNING_MANAGE_BACKFILL)
    @Operation(summary = "Get progress for an executor run",
            description = "Polling endpoint for the admin UI. Returns 404 if the run id is unknown.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Run summary"),
            @ApiResponse(responseCode = "404", description = "Unknown run id")
    })
    public Mono<ResponseEntity<RunSummary>> getRun(@PathVariable UUID runId) {
        return earningScheduleRunRepository.findById(runId)
                .map(run -> ResponseEntity.ok(RunSummary.of(run)))
                .defaultIfEmpty(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    @GetMapping
    @RequiresPermission(Permissions.PREMIUM_EARNING_VIEW_DEBUG)
    @Operation(summary = "List raw earning_schedule rows for a policy (dev-only)",
            description = "Diagnostic endpoint - production tenant admin roles do not carry premium.earning:view_debug.")
    public Flux<EarningSchedule> list(
            @RequestParam UUID policyId,
            @RequestParam String policySource) {
        return earningScheduleRepository.findByPolicyIdAndPolicySource(policyId, policySource);
    }

    /** Wire-shape projection of an {@link EarningScheduleRun}. */
    public record RunSummary(
            UUID id,
            UUID tenantId,
            String runKind,
            UUID triggerReference,
            String status,
            java.time.Instant startedAt,
            java.time.Instant finishedAt,
            java.time.Instant lastHeartbeatAt,
            UUID lastProcessedPolicyId,
            int policiesProcessed,
            int periodsWritten,
            String errorMessage
    ) {
        public static RunSummary of(EarningScheduleRun run) {
            return new RunSummary(
                    run.getId(),
                    run.getTenantId(),
                    run.getRunKind(),
                    run.getTriggerReference(),
                    run.getStatus(),
                    run.getStartedAt(),
                    run.getFinishedAt(),
                    run.getLastHeartbeatAt(),
                    run.getLastProcessedPolicyId(),
                    run.getPoliciesProcessed(),
                    run.getPeriodsWritten(),
                    run.getErrorMessage());
        }
    }
}

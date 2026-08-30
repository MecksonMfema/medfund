package com.medfund.user.controller;

import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.security.RequiresPermission;
import com.medfund.user.dto.AutoTransitionRequest;
import com.medfund.user.dto.CohortLossComponentBalanceResponse;
import com.medfund.user.dto.CohortLossComponentHistoryResponse;
import com.medfund.user.dto.CohortStatusHistoryResponse;
import com.medfund.user.dto.CreateIfrs17CohortRequest;
import com.medfund.user.dto.Ifrs17CohortResponse;
import com.medfund.user.dto.LockInYieldCurveRequest;
import com.medfund.user.dto.LockInYieldCurveResponse;
import com.medfund.user.dto.RecordLossComponentMovementRequest;
import com.medfund.user.dto.UpdateIfrs17CohortRequest;
import com.medfund.user.service.CohortLossComponentService;
import com.medfund.user.service.CohortStatusHistoryService;
import com.medfund.user.service.Ifrs17CohortLockInService;
import com.medfund.user.service.Ifrs17CohortService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/underwriting/cohorts")
@Tag(name = "Underwriting — IFRS 17 Cohorts",
     description = "Manage IFRS 17 cohort dimension (portfolio × year × type)")
@SecurityRequirement(name = "bearer-jwt")
@RequiredArgsConstructor
public class Ifrs17CohortController {

    private final Ifrs17CohortService service;
    private final CohortStatusHistoryService statusHistoryService;
    private final CohortLossComponentService lossComponentService;
    private final Ifrs17CohortLockInService lockInService;

    @GetMapping
    @Operation(summary = "List IFRS 17 cohorts",
               description = "Active cohorts by default; pass includeInactive=true to see soft-deleted rows. Filter by portfolioId to scope.")
    public Flux<Ifrs17CohortResponse> findAll(@RequestParam(defaultValue = "false") boolean includeInactive,
                                              @RequestParam(required = false) UUID portfolioId) {
        if (portfolioId != null) {
            return service.findByPortfolio(portfolioId).map(Ifrs17CohortResponse::from);
        }
        return service.findAll(includeInactive).map(Ifrs17CohortResponse::from);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get an IFRS 17 cohort by ID")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Cohort found"),
        @ApiResponse(responseCode = "404", description = "Cohort not found")
    })
    public Mono<Ifrs17CohortResponse> findById(@PathVariable UUID id) {
        return service.findById(id).map(Ifrs17CohortResponse::from);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission("underwriting.cohort:manage")
    @Operation(summary = "Create an IFRS 17 cohort")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Cohort created"),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "403", description = "Missing underwriting.cohort:manage"),
        @ApiResponse(responseCode = "404", description = "Parent portfolio not found"),
        @ApiResponse(responseCode = "409", description = "Duplicate (portfolio, year, type)")
    })
    public Mono<Ifrs17CohortResponse> create(@Valid @RequestBody CreateIfrs17CohortRequest request,
                                             @AuthenticationPrincipal Jwt jwt) {
        return service.create(request, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(Ifrs17CohortResponse::from);
    }

    @PutMapping("/{id}")
    @RequiresPermission("underwriting.cohort:manage")
    @Operation(summary = "Update an IFRS 17 cohort")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Cohort updated"),
        @ApiResponse(responseCode = "404", description = "Cohort not found"),
        @ApiResponse(responseCode = "403", description = "Missing underwriting.cohort:manage"),
        @ApiResponse(responseCode = "409", description = "Duplicate (portfolio, year, type)")
    })
    public Mono<Ifrs17CohortResponse> update(@PathVariable UUID id,
                                             @Valid @RequestBody UpdateIfrs17CohortRequest request,
                                             @AuthenticationPrincipal Jwt jwt) {
        return service.update(id, request, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(Ifrs17CohortResponse::from);
    }

    @GetMapping("/{id}/status-history")
    @Operation(summary = "List cohort_type transition history for a cohort",
               description = "Newest transition first. Rows land here on MANUAL edits "
                             + "via update() and on AUTO onerous-test transitions from ai-service (§15).")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "History rows returned (may be empty)"),
        @ApiResponse(responseCode = "404", description = "Cohort not found")
    })
    public Flux<CohortStatusHistoryResponse> statusHistory(@PathVariable UUID id) {
        return statusHistoryService.findByCohortId(id).map(CohortStatusHistoryResponse::from);
    }

    @GetMapping("/{id}/loss-component")
    @Operation(summary = "List loss-component movements for a cohort",
               description = "Newest movement first. INITIAL_RECOGNITION adds; RELEASE / REVERSAL / "
                             + "RECLASSIFICATION_TO_NON_ONEROUS subtract. Rows land here on MANUAL admin "
                             + "adjustments and on AUTO onerous-test compute (§15).")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Movement rows returned (may be empty)"),
        @ApiResponse(responseCode = "404", description = "Cohort not found")
    })
    public Flux<CohortLossComponentHistoryResponse> lossComponentHistory(@PathVariable UUID id) {
        return lossComponentService.findByCohortId(id).map(CohortLossComponentHistoryResponse::from);
    }

    @GetMapping("/{id}/loss-component/balance")
    @Operation(summary = "Current loss-component balance for a (cohort, currency)",
               description = "Reads the cohort_loss_component_current matview. Returns 0 when no movements "
                             + "have been recorded or the matview has not been refreshed since the first "
                             + "movement.")
    public Mono<CohortLossComponentBalanceResponse> lossComponentBalance(
            @PathVariable UUID id,
            @RequestParam String currency) {
        return lossComponentService.openingBalance(id, currency)
                .map(balance -> new CohortLossComponentBalanceResponse(id, currency, balance));
    }

    @PostMapping("/{id}/loss-component")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission("underwriting.cohort:manage")
    @Operation(summary = "Record a manual loss-component movement",
               description = "MANUAL adjustment path. AUTO transitions from §15 ai-service compute bypass "
                             + "this endpoint and call the service directly.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Movement recorded"),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "403", description = "Missing underwriting.cohort:manage"),
        @ApiResponse(responseCode = "404", description = "Cohort not found")
    })
    public Mono<CohortLossComponentHistoryResponse> recordLossComponentMovement(
            @PathVariable UUID id,
            @Valid @RequestBody RecordLossComponentMovementRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        String actorIdStr = AuditActor.id(jwt);
        UUID actorUuid = actorIdStr != null && !AuditActor.SYSTEM_ID.equals(actorIdStr)
                ? UUID.fromString(actorIdStr)
                : null;
        return lossComponentService.recordMovement(
                id,
                request.movementType(),
                request.amount(),
                request.currency(),
                null,
                actorUuid,
                AuditActor.email(jwt),
                request.reasonNote()
        ).map(CohortLossComponentHistoryResponse::from);
    }

    @PostMapping("/{id}/status-history/auto-transition")
    @Operation(summary = "Record an auto-transition from the ai-service onerous test",
               description = "Service-to-service callback fired by the ai-service §15 onerous-test "
                             + "compute when a cohort flips ONEROUS or recovers back to NON_ONEROUS. "
                             + "Flips ifrs17_cohort.cohort_type, appends a status-history row "
                             + "(source=AUTO), and — on AUTO_TEST_FAILED — writes an INITIAL_RECOGNITION "
                             + "loss-component movement for the positive gap. Emits AuditEvent + "
                             + "medfund.ifrs17.material-event via the existing publishers. Idempotent "
                             + "on (cohortId, sourceRunId): repeat calls return the original row "
                             + "without duplicate writes or events. Not a tenant-admin surface — a "
                             + "service-scoped JWT (SCOPE_service_ifrs17) is expected.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Handled (may have been a no-op idempotency return)"),
        @ApiResponse(responseCode = "400", description = "Validation error (missing currency on failed-test amount)"),
        @ApiResponse(responseCode = "404", description = "Cohort not found"),
        @ApiResponse(responseCode = "409", description = "Cohort.cohort_type does not match request.fromStatus — stale snapshot")
    })
    public Mono<CohortStatusHistoryResponse> recordAutoTransition(@PathVariable UUID id,
                                                                   @Valid @RequestBody AutoTransitionRequest request) {
        return statusHistoryService.recordAutoTransition(id, request)
                .map(CohortStatusHistoryResponse::from);
    }

    @PostMapping("/{id}/lock-in-yield-curve")
    @Operation(summary = "Lock in the yield curve snapshot for a cohort",
               description = "IFRS 17.44: the discount curve at initial recognition of a group "
                             + "of contracts is locked in for CSM interest accretion. This endpoint "
                             + "is idempotent — the write only happens when locked_in_at IS NULL, so "
                             + "contributions-service can call it on every policy-issued event without "
                             + "a pre-check. The response's `firstPolicy` flag says whether this call "
                             + "was the one that actually wrote.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Handled (write may have been a no-op)"),
        @ApiResponse(responseCode = "404", description = "Cohort not found")
    })
    public Mono<LockInYieldCurveResponse> lockInYieldCurve(@PathVariable UUID id,
                                                          @Valid @RequestBody LockInYieldCurveRequest request) {
        return lockInService.lockInIfFirstPolicy(id, request.currency(), request.effectiveDate())
                .map(firstPolicy -> new LockInYieldCurveResponse(id, firstPolicy));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission("underwriting.cohort:manage")
    @Operation(summary = "Soft-delete an IFRS 17 cohort",
               description = "Sets is_active=false. Refuses (409) if any policies still reference this cohort.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Cohort soft-deleted"),
        @ApiResponse(responseCode = "404", description = "Cohort not found"),
        @ApiResponse(responseCode = "403", description = "Missing underwriting.cohort:manage"),
        @ApiResponse(responseCode = "409", description = "Cannot delete — policies still reference this cohort")
    })
    public Mono<Void> delete(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return service.softDelete(id, AuditActor.id(jwt), AuditActor.email(jwt));
    }
}

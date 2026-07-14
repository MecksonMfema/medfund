package com.medfund.contributions.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.contributions.dto.BillingCommitResponse;
import com.medfund.contributions.dto.BillingPreviewResponse;
import com.medfund.contributions.dto.ChargePreviewResponse;
import com.medfund.contributions.dto.CommitBillingRequest;
import com.medfund.contributions.dto.ContributionFilterParams;
import com.medfund.contributions.dto.ContributionResponse;
import com.medfund.contributions.dto.ContributionRow;
import com.medfund.contributions.dto.EnqueueBillingRequest;
import com.medfund.contributions.dto.PageResponse;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import com.medfund.contributions.dto.EnqueueBillingResponse;
import com.medfund.contributions.dto.GenerateBillingRequest;
import com.medfund.contributions.dto.PreviewBillingRequest;
import com.medfund.contributions.service.BillingService;
import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.scheduler.JobDispatcher;
import com.medfund.shared.scheduler.JobType;
import com.medfund.shared.scheduler.ScheduledJobService;
import com.medfund.shared.tenant.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/contributions")
@Tag(name = "Contributions", description = "Contribution billing, payment recording, and balance tracking")
@SecurityRequirement(name = "bearer-jwt")
public class ContributionController {

    private final BillingService billingService;
    private final ScheduledJobService scheduledJobService;
    private final JobDispatcher jobDispatcher;
    private final ObjectMapper objectMapper;

    public ContributionController(BillingService billingService,
                                  ScheduledJobService scheduledJobService,
                                  JobDispatcher jobDispatcher,
                                  ObjectMapper objectMapper) {
        this.billingService = billingService;
        this.scheduledJobService = scheduledJobService;
        this.jobDispatcher = jobDispatcher;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/member/{memberId}")
    @Operation(summary = "List contributions by member")
    public Flux<ContributionResponse> findByMemberId(@PathVariable UUID memberId) {
        return billingService.findContributionsByMemberId(memberId).map(ContributionResponse::from);
    }

    @GetMapping("/group/{groupId}")
    @Operation(summary = "List contributions by group")
    public Flux<ContributionResponse> findByGroupId(@PathVariable UUID groupId) {
        return billingService.findContributionsByGroupId(groupId).map(ContributionResponse::from);
    }

    @GetMapping("/status/{status}")
    @Operation(summary = "List contributions by status (unpaginated — prefer /page)")
    public Flux<ContributionResponse> findByStatus(@PathVariable String status) {
        return billingService.findContributionsByStatus(status).map(ContributionResponse::from);
    }

    @GetMapping("/page")
    @Operation(summary = "Server-side paginated, sortable, filterable contribution-statements list",
        description = "Feeds /tenant/billing/contributions. Joins members + groups + schemes "
                + "into every row so the operational statements table renders inline. "
                + "Sortable keys: memberName, groupName, schemeName, amount, currencyCode, "
                + "status, periodStart, periodEnd, paidAt, createdAt.")
    @ApiResponse(responseCode = "200", description = "Page of contributions")
    public Mono<PageResponse<ContributionRow>> searchPaged(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID memberId,
            @RequestParam(required = false) UUID groupId,
            @RequestParam(required = false) UUID schemeId,
            @RequestParam(required = false) String currencyCode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStartFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStartTo,
            @RequestParam(required = false) String q,
            @RequestParam(required = false, defaultValue = "periodStart") String sortKey,
            @RequestParam(required = false, defaultValue = "desc") String sortDirection,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "50") int size) {
        var params = new ContributionFilterParams(
                status, memberId, groupId, schemeId, currencyCode,
                periodStartFrom, periodStartTo, q, sortKey, sortDirection, page, size);
        return billingService.searchContributionsPaged(params);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get contribution by ID")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Contribution found"),
        @ApiResponse(responseCode = "404", description = "Contribution not found")
    })
    public Mono<ContributionResponse> findById(@PathVariable UUID id) {
        return billingService.findContributionById(id).map(ContributionResponse::from);
    }

    @PostMapping("/preview")
    @Operation(summary = "Preview a billing run",
        description = "Resolves the population that would be billed and runs tenant pricing rules. " +
                "Returns counts, per-currency totals, and a sample. No persistence.")
    @ApiResponse(responseCode = "200", description = "Preview computed")
    public Mono<BillingPreviewResponse> previewBilling(@Valid @RequestBody PreviewBillingRequest request) {
        return billingService.previewBilling(request);
    }

    @GetMapping("/charge-preview")
    @Operation(summary = "Live per-subject charge projection for the next billing cycle",
        description = "Read-only projection of what a single group or individual would owe in the "
                + "next billing cycle, itemized by member/dependant. Reuses the same candidate resolver + "
                + "pricing rules the real commit uses, so future scheme upgrades/downgrades (via "
                + "billing_age_group_id + billing_override_effective_from) and per-member custom pricing "
                + "(billing_override_amount) are already reflected. Excludes members whose "
                + "termination_date lands before the projected periodStart — those are on their last "
                + "serviced cycle and shouldn't be charged for the next one. Never persists a row.")
    @ApiResponse(responseCode = "200", description = "Projection computed")
    public Mono<ChargePreviewResponse> chargePreview(
            @io.swagger.v3.oas.annotations.Parameter(description = "GROUP or MEMBER")
            @RequestParam String subjectType,
            @io.swagger.v3.oas.annotations.Parameter(description = "Group id (for GROUP) or member id (for MEMBER)")
            @RequestParam UUID subjectId,
            @io.swagger.v3.oas.annotations.Parameter(description = "Optional ISO-4217 filter; omit to include every currency")
            @RequestParam(required = false) String currency) {
        return billingService.chargePreview(subjectType, subjectId, currency);
    }

    @PostMapping("/commit")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Commit a billing run",
        description = "Persists contribution rows for the same selection and stamps " +
                "billing_cycle_config.last_committed_at. Returns 409 with `remainingMinutes` " +
                "if another commit ran within the configured cooldown.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Billing cycle committed"),
        @ApiResponse(responseCode = "409", description = "Cooldown active")
    })
    public Mono<BillingCommitResponse> commitBilling(@Valid @RequestBody CommitBillingRequest request,
                                                     @AuthenticationPrincipal Jwt jwt) {
        return billingService.commitBilling(request, AuditActor.id(jwt), AuditActor.email(jwt));
    }

    @PostMapping("/billing/enqueue")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Enqueue a billing preview or commit as a background job",
        description = "Creates an ad-hoc scheduled-job config bound to the current tenant, kicks it via the " +
                "JobDispatcher, and returns the runId immediately. The UI short-polls " +
                "GET /api/v1/scheduled-jobs/{configId}/runs?limit=1 to surface progress and the result_payload. " +
                "Use this in place of the synchronous /preview and /commit endpoints when the tenant's member " +
                "population is large enough for the request to time out.")
    @ApiResponses({
        @ApiResponse(responseCode = "202", description = "Job enqueued and running"),
        @ApiResponse(responseCode = "400", description = "Invalid request body")
    })
    public Mono<EnqueueBillingResponse> enqueueBilling(@Valid @RequestBody EnqueueBillingRequest request,
                                                       @AuthenticationPrincipal Jwt jwt) {
        JobType jobType = "preview".equals(request.kind()) ? JobType.BILLING_PREVIEW : JobType.BILLING_COMMIT;
        String actorId = AuditActor.id(jwt);
        String actorEmail = AuditActor.email(jwt);
        String settings;
        try {
            settings = buildSettings(request, actorId, actorEmail);
        } catch (JsonProcessingException e) {
            return Mono.error(new IllegalArgumentException("Could not serialise billing job settings", e));
        }
        String name = "Billing " + request.kind() + " " + request.periodStart() + "..." + request.periodEnd();

        return Mono.deferContextual(ctx -> {
            UUID tenantUuid = parseUuidOrNull(TenantContext.get(ctx));
            return scheduledJobService.createAdHocConfig(tenantUuid, jobType.name(), name, settings, actorId)
                .flatMap(config -> jobDispatcher.runNowAsync(config, parseUuidOrNull(actorId), actorEmail)
                    .map(run -> new EnqueueBillingResponse(
                        run.getConfigId(),
                        run.getId(),
                        run.getStatus(),
                        run.getStartedAt())));
        });
    }

    private String buildSettings(EnqueueBillingRequest req, String actorId, String actorEmail)
            throws JsonProcessingException {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("periodStart", req.periodStart().toString());
        map.put("periodEnd", req.periodEnd().toString());
        if (req.groupIds() != null && !req.groupIds().isEmpty()) map.put("groupIds", req.groupIds());
        if (req.memberIds() != null && !req.memberIds().isEmpty()) map.put("memberIds", req.memberIds());
        if (req.insuranceLine() != null && !req.insuranceLine().isBlank()) {
            map.put("insuranceLine", req.insuranceLine());
        }
        if ("commit".equals(req.kind())) {
            map.put("actorId", actorId);
            map.put("actorEmail", actorEmail);
        }
        return objectMapper.writeValueAsString(map);
    }

    private static UUID parseUuidOrNull(String value) {
        if (value == null || value.isBlank()) return null;
        try { return UUID.fromString(value); } catch (IllegalArgumentException e) { return null; }
    }

    @PostMapping("/generate-billing")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "[Deprecated] Generate a single contribution row",
        description = "Legacy single-row endpoint. Prefer /preview + /commit which iterates the chosen members.",
        deprecated = true)
    public Mono<Long> generateBilling(@Valid @RequestBody GenerateBillingRequest request,
                                      @AuthenticationPrincipal Jwt jwt) {
        return billingService.generateBilling(request, AuditActor.id(jwt), AuditActor.email(jwt));
    }

    @PostMapping("/billing/revoke")
    @Operation(summary = "Revoke a billing run for next month",
        description = "Deletes every contribution + invoice for the (period, line) so the operator can re-commit. " +
                "Only the calendar month immediately following today is revocable — once that month becomes the " +
                "current month the contributions are active and any correction has to go through the corrections flow.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Revoke succeeded"),
        @ApiResponse(responseCode = "422", description = "Period outside the next-month revoke window")
    })
    public Mono<com.medfund.contributions.dto.BillingRevokeResponse> revokeBilling(
            @Valid @RequestBody com.medfund.contributions.dto.RevokeBillingRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return billingService.revokeBilling(request, AuditActor.id(jwt), AuditActor.email(jwt));
    }

    @PostMapping("/{id}/pay")
    @Operation(summary = "Record contribution payment")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Payment recorded"),
        @ApiResponse(responseCode = "404", description = "Contribution not found")
    })
    public Mono<ContributionResponse> recordPayment(@PathVariable UUID id,
                                                    @RequestParam String paymentMethod,
                                                    @RequestParam(required = false) String paymentReference,
                                                    @AuthenticationPrincipal Jwt jwt) {
        return billingService.recordPayment(id, paymentMethod, paymentReference,
                        AuditActor.id(jwt), AuditActor.email(jwt))
                .map(ContributionResponse::from);
    }
}

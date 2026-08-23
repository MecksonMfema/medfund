package com.medfund.user.endorsement.controller;

import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.security.Permissions;
import com.medfund.shared.security.RequiresPermission;
import com.medfund.user.dto.PageResponse;
import com.medfund.user.endorsement.dto.CreateEndorsementRequest;
import com.medfund.user.endorsement.dto.EndorsementResponse;
import com.medfund.user.endorsement.dto.VoidEndorsementRequest;
import com.medfund.user.endorsement.service.PolicyEndorsementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * Policy-endorsement REST surface (Phase 12 §C). Drafter creates a DRAFT;
 * below-threshold rows auto-commit (see
 * {@link PolicyEndorsementService#createDraft}), above-threshold rows park
 * for supervisor approval. Void available on DRAFT/APPROVED — COMMITTED /
 * COMPUTED are terminal (matches the commission-adjustment precedent).
 *
 * <p>Two permission scopes:
 * <ul>
 *   <li><b>Drafter</b> ({@code policy:draft_endorsement}) — creates DRAFT
 *       and views the queue.</li>
 *   <li><b>Supervisor</b> ({@code policy:approve_endorsement}) — approves,
 *       commits, voids, and marks COMPUTED.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/endorsements")
@RequiredArgsConstructor
@Tag(name = "Policy Endorsements",
     description = "Four-eyes policy-endorsement lifecycle. Drafter creates a DRAFT; below-threshold "
                 + "auto-commits, above-threshold parks for supervisor approval. Publishes "
                 + "medfund.user.policy-endorsed on COMMIT so contributions-service reruns the "
                 + "earning-schedule for affected periods.")
@SecurityRequirement(name = "bearer-jwt")
public class EndorsementController {

    private final PolicyEndorsementService service;

    @GetMapping
    @RequiresPermission(Permissions.POLICY_DRAFT_ENDORSEMENT)
    @Operation(summary = "Paginated queue of endorsements",
            description = "Returns DRAFT + APPROVED rows by default. Pass ?status=DRAFT / APPROVED / "
                        + "COMMITTED / COMPUTED / VOIDED to narrow. Sorted oldest-first so the "
                        + "approver sees long-waiting DRAFTs at the top.")
    public Mono<PageResponse<EndorsementResponse>> queue(
            @Parameter(description = "Optional single-status filter; omit for DRAFT+APPROVED")
            @RequestParam(required = false) String status,
            @RequestParam(required = false, defaultValue = "0") @Min(0) int page,
            @RequestParam(required = false, defaultValue = "50") @Min(1) @Max(200) int size) {
        List<String> statuses = (status != null && !status.isBlank())
                ? List.of(status)
                : List.of("DRAFT", "APPROVED");
        return service.queue(statuses, page, size).collectList()
                .zipWith(service.queueCount(statuses),
                        (rows, total) -> PageResponse.of(rows, total, page, size));
    }

    @GetMapping("/{id}")
    @RequiresPermission(Permissions.POLICY_DRAFT_ENDORSEMENT)
    @Operation(summary = "Fetch a single endorsement by id")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Endorsement"),
            @ApiResponse(responseCode = "400", description = "Not found (id unknown)")
    })
    public Mono<EndorsementResponse> get(@PathVariable UUID id) {
        return service.findById(id);
    }

    @GetMapping("/by-policy")
    @RequiresPermission(Permissions.POLICY_DRAFT_ENDORSEMENT)
    @Operation(summary = "List endorsements for a specific policy",
            description = "Returns endorsements ordered newest-first. Used by the per-policy "
                        + "Endorsements tab on the tenant-admin policy-detail pages.")
    public Flux<EndorsementResponse> byPolicy(@RequestParam UUID policyId,
                                              @RequestParam String policySource) {
        return service.findByPolicy(policyId, policySource);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permissions.POLICY_DRAFT_ENDORSEMENT)
    @Operation(summary = "Create an endorsement (auto-commit or DRAFT)",
            description = "Drafter action. Snaps effectiveFrom to 1st-of-month, mints an "
                        + "END-YYYY-NNNNNN reference, and inserts. Reads the tenant "
                        + "endorsement-config: below-threshold (or disabled) auto-commits and "
                        + "fires medfund.user.policy-endorsed inline; at-or-above-threshold "
                        + "parks at DRAFT for supervisor approval.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Created (DRAFT or COMMITTED)"),
            @ApiResponse(responseCode = "400", description = "Request validation failed")
    })
    public Mono<EndorsementResponse> create(@Valid @RequestBody CreateEndorsementRequest body,
                                            @AuthenticationPrincipal Jwt jwt) {
        return service.createDraft(body, AuditActor.id(jwt), AuditActor.email(jwt));
    }

    @PutMapping("/{id}/approve")
    @RequiresPermission(Permissions.POLICY_APPROVE_ENDORSEMENT)
    @Operation(summary = "Approve a DRAFT endorsement (DRAFT → APPROVED)",
            description = "Supervisor action. Four-eyes invariant: approver actorId must differ "
                        + "from drafter actorId.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Approved"),
            @ApiResponse(responseCode = "409", description = "Not DRAFT, or approver == drafter (four-eyes)")
    })
    public Mono<EndorsementResponse> approve(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return service.approve(id, AuditActor.id(jwt), AuditActor.email(jwt));
    }

    @PutMapping("/{id}/commit")
    @RequiresPermission(Permissions.POLICY_APPROVE_ENDORSEMENT)
    @Operation(summary = "Commit an APPROVED endorsement (APPROVED → COMMITTED)",
            description = "Supervisor action. Publishes medfund.user.policy-endorsed on commit so "
                        + "contributions-service reruns the earning-schedule for periods on or "
                        + "after effectiveFrom.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Committed"),
            @ApiResponse(responseCode = "409", description = "Endorsement is not APPROVED")
    })
    public Mono<EndorsementResponse> commit(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return service.commit(id, AuditActor.id(jwt), AuditActor.email(jwt));
    }

    @PostMapping("/{id}/void")
    @RequiresPermission(Permissions.POLICY_APPROVE_ENDORSEMENT)
    @Operation(summary = "Void a DRAFT / APPROVED endorsement",
            description = "Reason is mandatory (recorded on voidedReason). COMMITTED / COMPUTED "
                        + "endorsements cannot be voided (terminal).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Voided"),
            @ApiResponse(responseCode = "400", description = "Missing void reason"),
            @ApiResponse(responseCode = "409", description = "Endorsement is COMMITTED / COMPUTED / already VOIDED")
    })
    public Mono<EndorsementResponse> voidEndorsement(@PathVariable UUID id,
                                                     @Valid @RequestBody VoidEndorsementRequest body,
                                                     @AuthenticationPrincipal Jwt jwt) {
        return service.voidEndorsement(id, body.reason(),
                AuditActor.id(jwt), AuditActor.email(jwt));
    }

    @PutMapping("/{id}/computed")
    @RequiresPermission(Permissions.POLICY_APPROVE_ENDORSEMENT)
    @Operation(summary = "Mark a COMMITTED endorsement COMPUTED",
            description = "Called by contributions-service after the retro earning-schedule "
                        + "recompute finishes (Phase 12 §C Phase 9). Also usable by an operator "
                        + "for manual reconciliation.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Marked COMPUTED"),
            @ApiResponse(responseCode = "409", description = "Endorsement is not COMMITTED")
    })
    public Mono<EndorsementResponse> markComputed(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return service.markComputed(id, AuditActor.id(jwt), AuditActor.email(jwt));
    }
}

package com.medfund.finance.producer.controller;

import com.medfund.finance.dto.PageResponse;
import com.medfund.finance.producer.dto.AdjustmentResponse;
import com.medfund.finance.producer.dto.CreateAdjustmentRequest;
import com.medfund.finance.producer.dto.VoidAdjustmentRequest;
import com.medfund.finance.producer.service.CommissionAdjustmentService;
import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.security.Permissions;
import com.medfund.shared.security.RequiresPermission;
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
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * Commission-adjustment REST surface (Phase 11 §B Phase 8). Four-eyes
 * lifecycle: drafter creates a DRAFT with a full justification, supervisor
 * approves + commits. Commit writes a compensating
 * {@code commission_transaction} row linked back to the target. Void is
 * available on DRAFT / APPROVED only — COMMITTED is terminal per the
 * facultative-cession precedent.
 *
 * <p>Roles:
 * <ul>
 *   <li><b>Drafter</b> ({@code finance.commission:draft_adjustment}) —
 *       creates the DRAFT.</li>
 *   <li><b>Supervisor</b> ({@code finance.commission:approve_adjustment}) —
 *       approves, commits, voids.</li>
 *   <li><b>Viewer</b> ({@code finance.commission:view}) — reads the queue
 *       and detail pages.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/commission/adjustments")
@RequiredArgsConstructor
@Tag(name = "Commission — Adjustments",
     description = "Four-eyes commission-adjustment lifecycle. Drafter creates a DRAFT with a "
                 + "justification; supervisor approves and commits (writes a compensating "
                 + "commission_transaction). Void available on DRAFT/APPROVED only.")
@SecurityRequirement(name = "bearer-jwt")
public class CommissionAdjustmentController {

    private final CommissionAdjustmentService service;

    @GetMapping
    @RequiresPermission(Permissions.COMMISSION_VIEW)
    @Operation(summary = "Paginated queue of commission adjustments",
            description = "Returns DRAFT + APPROVED rows by default. Pass ?status=DRAFT / APPROVED / "
                        + "COMMITTED / VOIDED to narrow. Sorted oldest-first so long-waiting "
                        + "adjustments surface at the top of the queue.")
    public Mono<PageResponse<AdjustmentResponse>> queue(
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
    @RequiresPermission(Permissions.COMMISSION_VIEW)
    @Operation(summary = "Fetch a single commission adjustment by id")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Adjustment"),
            @ApiResponse(responseCode = "400", description = "Not found (id unknown)")
    })
    public Mono<AdjustmentResponse> get(@PathVariable UUID id) {
        return service.findById(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permissions.COMMISSION_DRAFT_ADJUSTMENT)
    @Operation(summary = "Create a DRAFT commission adjustment",
            description = "Drafter action. Validates the target commission_transaction exists, "
                        + "mints a COMM-ADJ-YYYY-NNNNNN reference, and inserts. Currency is "
                        + "inferred from the target row. Emits AuditEvent action=CREATE.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Created"),
            @ApiResponse(responseCode = "400", description = "Request validation failed (missing "
                    + "target, justification < 20 chars, or zero amount)")
    })
    public Mono<AdjustmentResponse> create(@Valid @RequestBody CreateAdjustmentRequest body,
                                           @AuthenticationPrincipal Jwt jwt) {
        return service.createDraft(body, AuditActor.id(jwt), AuditActor.email(jwt));
    }

    @PutMapping("/{id}/approve")
    @RequiresPermission(Permissions.COMMISSION_APPROVE_ADJUSTMENT)
    @Operation(summary = "Approve a DRAFT adjustment (DRAFT → APPROVED)",
            description = "Supervisor action. Four-eyes invariant: approver actorId must differ "
                        + "from drafter actorId. Emits AuditEvent action=APPROVE.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Approved"),
            @ApiResponse(responseCode = "409", description = "Adjustment is not DRAFT, or approver "
                    + "matches drafter (four-eyes)")
    })
    public Mono<AdjustmentResponse> approve(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return service.approve(id, AuditActor.id(jwt), AuditActor.email(jwt));
    }

    @PutMapping("/{id}/commit")
    @RequiresPermission(Permissions.COMMISSION_APPROVE_ADJUSTMENT)
    @Operation(summary = "Commit an APPROVED adjustment (APPROVED → COMMITTED)",
            description = "Supervisor action. Writes a compensating commission_transaction row "
                        + "linked back via committedTxnId; the compensating row's "
                        + "reversalOfTxnId points at the target. Emits AuditEvent action=COMMIT "
                        + "on the adjustment + action=CREATE on the compensating transaction.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Committed"),
            @ApiResponse(responseCode = "409", description = "Adjustment is not APPROVED")
    })
    public Mono<AdjustmentResponse> commit(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return service.commit(id, AuditActor.id(jwt), AuditActor.email(jwt));
    }

    @PostMapping("/{id}/void")
    @RequiresPermission(Permissions.COMMISSION_APPROVE_ADJUSTMENT)
    @Operation(summary = "Void a DRAFT / APPROVED adjustment",
            description = "Reason is mandatory (recorded on voided_reason). COMMITTED adjustments "
                        + "cannot be voided (terminal, matches facultative precedent). Emits "
                        + "AuditEvent action=VOID.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Voided"),
            @ApiResponse(responseCode = "400", description = "Missing void reason"),
            @ApiResponse(responseCode = "409", description = "Adjustment is COMMITTED or already VOIDED")
    })
    public Mono<AdjustmentResponse> voidAdjustment(@PathVariable UUID id,
                                                   @Valid @RequestBody VoidAdjustmentRequest body,
                                                   @AuthenticationPrincipal Jwt jwt) {
        return service.voidAdjustment(id, body.reason(),
                AuditActor.id(jwt), AuditActor.email(jwt));
    }
}

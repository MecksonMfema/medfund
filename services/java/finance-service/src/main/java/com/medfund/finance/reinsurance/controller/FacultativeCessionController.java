package com.medfund.finance.reinsurance.controller;

import com.medfund.finance.client.ClaimsClient;
import com.medfund.finance.dto.PageResponse;
import com.medfund.finance.reinsurance.dto.CessionResponse;
import com.medfund.finance.reinsurance.dto.CreateFacultativeCessionRequest;
import com.medfund.finance.reinsurance.dto.FacultativeCandidateRow;
import com.medfund.finance.reinsurance.dto.VoidFacultativeCessionRequest;
import com.medfund.finance.reinsurance.repository.CessionRepository;
import com.medfund.finance.reinsurance.service.FacultativeCessionService;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Facultative-cession REST surface (Phase 7). Two roles interact with
 * this controller:
 *
 * <ul>
 *   <li><b>Underwriter</b> ({@code finance.reinsurance:cede_facultative})
 *       browses candidates + creates DRAFT rows.</li>
 *   <li><b>Supervisor</b> ({@code finance.reinsurance:approve_facultative})
 *       moves DRAFT → APPROVED → CEDED and voids pre-terminal rows.</li>
 * </ul>
 *
 * <p>The queue endpoint lets any {@code finance.reinsurance:view} user
 * see progress without acting on it — the sidebar surfaces this queue as
 * a badge count on the finance nav.
 */
@RestController
@RequestMapping("/api/v1/reinsurance/facultative")
@RequiredArgsConstructor
@Tag(name = "Reinsurance — Facultative",
     description = "Facultative-cession lifecycle: underwriter drops a DRAFT against an ACTIVE treaty, "
                 + "supervisor approves + commits. Committed rows show on the cession bordereau. Voiding "
                 + "a DRAFT/APPROVED cascade-writes-off any linked recovery.")
@SecurityRequirement(name = "bearer-jwt")
public class FacultativeCessionController {

    private final FacultativeCessionService service;
    private final ClaimsClient claimsClient;
    private final CessionRepository cessionRepository;

    /**
     * Adjudicated claims above {@code minAmount} the underwriter can pick
     * up to cede. Filters to {@code status=ADJUDICATED} on the peer
     * claims-service {@code /page} endpoint. {@code minAmount} defaults
     * to zero — callers should pass a meaningful threshold to avoid
     * pulling every low-value claim.
     */
    @GetMapping("/candidates")
    @RequiresPermission(Permissions.REINSURANCE_CEDE_FACULTATIVE)
    @Operation(summary = "Browse adjudicated claim candidates for facultative cession",
            description = "Server-side filter on status=ADJUDICATED + insuranceLine + minAmount. "
                        + "Returns up to `size` rows (default 50) per call. Rows do not carry a "
                        + "'already ceded' flag today — a race between browse and cede is caught "
                        + "at write-time and surfaces as 409 Conflict.")
    public Mono<List<FacultativeCandidateRow>> candidates(
            @Parameter(description = "Minimum approvedAmount — rows below this are dropped")
            @RequestParam(required = false) BigDecimal minAmount,
            @Parameter(description = "Optional insurance-line filter (HEALTH, LIFE, …)")
            @RequestParam(required = false) String insuranceLine,
            @RequestParam(required = false, defaultValue = "0") @Min(0) int page,
            @RequestParam(required = false, defaultValue = "50") @Min(1) @Max(100) int size) {
        return claimsClient.findFacultativeCandidates(insuranceLine, minAmount, page, size);
    }

    /**
     * Facultative queue: pre-terminal (DRAFT / APPROVED) rows the
     * supervisor works through. Optional {@code status} filter narrows to
     * one bucket. Sorted oldest-first so long-waiting rows surface.
     */
    @GetMapping("/queue")
    @RequiresPermission(Permissions.REINSURANCE_VIEW)
    @Operation(summary = "List facultative cessions in a working state",
            description = "Returns DRAFT + APPROVED by default; pass ?status=DRAFT or ?status=APPROVED "
                        + "to narrow. Sorted oldest-first — the supervisor should clear the top of the "
                        + "queue first.")
    public Mono<PageResponse<CessionResponse>> queue(
            @RequestParam(required = false) String status,
            @RequestParam(required = false, defaultValue = "0")  @Min(0)  int page,
            @RequestParam(required = false, defaultValue = "50") @Min(1) @Max(200) int size) {
        int offset = page * size;
        boolean narrowed = status != null && !status.isBlank();
        var content = (narrowed
                    ? cessionRepository.findFacultativeByStatus(status, offset, size)
                    : cessionRepository.findFacultativeQueue(offset, size))
                .map(CessionResponse::from)
                .collectList();
        var count = narrowed
                ? cessionRepository.countFacultativeByStatus(status)
                : cessionRepository.countFacultativeQueue();
        return content.zipWith(count, (rows, total) -> PageResponse.of(rows, total, page, size));
    }

    /**
     * Underwriter creates a DRAFT facultative cession. The caller must
     * supply {@code insuranceLine} on the query string — the service
     * needs it to validate the treaty covers the line, and pushing that
     * lookup back into the service would drag the claims-service HTTP
     * dependency into the write path. The candidates browse already
     * carries the line on every row, so the client just passes it back.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permissions.REINSURANCE_CEDE_FACULTATIVE)
    @Operation(summary = "Create a DRAFT facultative cession against an ACTIVE treaty",
            description = "Validates: treaty ACTIVE, insurance line covered by treaty, and no live "
                        + "LOSS cession already on (claim, treaty). Emits AuditEvent action=CREATE.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Created"),
            @ApiResponse(responseCode = "400", description = "Request validation failed"),
            @ApiResponse(responseCode = "409", description = "Treaty not ACTIVE, line not covered, or duplicate")
    })
    public Mono<CessionResponse> create(
            @Parameter(description = "Insurance line of the underlying claim")
            @RequestParam String insuranceLine,
            @Valid @RequestBody CreateFacultativeCessionRequest body,
            @AuthenticationPrincipal Jwt jwt) {
        return service.createDraft(body, insuranceLine, AuditActor.id(jwt), AuditActor.email(jwt));
    }

    @PutMapping("/{id}/approve")
    @RequiresPermission(Permissions.REINSURANCE_APPROVE_FACULTATIVE)
    @Operation(summary = "Approve a DRAFT facultative cession (DRAFT → APPROVED)",
            description = "Emits AuditEvent action=APPROVE. Idempotent only in the sense that a "
                        + "second call on an APPROVED cession returns 409 with a named message.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Approved"),
            @ApiResponse(responseCode = "409", description = "Cession is not DRAFT")
    })
    public Mono<CessionResponse> approve(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return service.approve(id, AuditActor.id(jwt), AuditActor.email(jwt));
    }

    @PutMapping("/{id}/commit")
    @RequiresPermission(Permissions.REINSURANCE_APPROVE_FACULTATIVE)
    @Operation(summary = "Commit an APPROVED facultative cession (APPROVED → CEDED)",
            description = "The cession appears on the next cession bordereau export. Writes an "
                        + "EXPECTED Recovery row so the recoveries bordereau picks it up. Emits "
                        + "AuditEvent action=COMMIT on the cession + action=CREATE on the recovery.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Committed"),
            @ApiResponse(responseCode = "409", description = "Cession is not APPROVED")
    })
    public Mono<CessionResponse> commit(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return service.commit(id, AuditActor.id(jwt), AuditActor.email(jwt));
    }

    @PostMapping("/{id}/void")
    @RequiresPermission(Permissions.REINSURANCE_APPROVE_FACULTATIVE)
    @Operation(summary = "Void a DRAFT/APPROVED facultative cession",
            description = "Reason is mandatory — recorded on cession.voided_reason. Cascade-writes-off "
                        + "any EXPECTED / INVOICED Recovery with the reason prefixed 'Cession voided: '. "
                        + "CEDED facultative cessions must be commuted, not voided.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Voided"),
            @ApiResponse(responseCode = "400", description = "Missing void reason"),
            @ApiResponse(responseCode = "409", description = "Cession is CEDED or already VOIDED")
    })
    public Mono<CessionResponse> voidCession(@PathVariable UUID id,
                                             @Valid @RequestBody VoidFacultativeCessionRequest body,
                                             @AuthenticationPrincipal Jwt jwt) {
        return service.voidCession(id, body.reason(), AuditActor.id(jwt), AuditActor.email(jwt));
    }
}

package com.medfund.claims.siu.controller;

import com.medfund.claims.siu.dto.AddEvidenceRequest;
import com.medfund.claims.siu.dto.AddReferralRequest;
import com.medfund.claims.siu.dto.AssignCaseRequest;
import com.medfund.claims.siu.dto.CloseCaseRequest;
import com.medfund.claims.siu.dto.ProposeClosureRequest;
import com.medfund.claims.siu.dto.RejectClosureRequest;
import com.medfund.claims.siu.dto.ReopenCaseRequest;
import com.medfund.claims.siu.dto.SiuCaseResponse;
import com.medfund.claims.siu.dto.SiuCaseSummaryResponse;
import com.medfund.claims.siu.dto.SiuEvidenceResponse;
import com.medfund.claims.siu.dto.SiuReferralResponse;
import com.medfund.claims.siu.service.SiuCaseQueryService;
import com.medfund.claims.siu.service.SiuCaseService;
import com.medfund.shared.security.Permissions;
import com.medfund.shared.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * SIU (Special Investigations Unit) case-management endpoints — MVP 3-state
 * machine per Phase 19 §A. Manual case-open + assignment endpoints land in
 * §B Phase 7; four-eyes approve/reject in §B Phase 8.
 */
@Tag(name = "SIU Cases",
        description = "Special Investigations Unit case management (Phase 19 §A MVP).")
@RestController
@RequestMapping("/api/v1/siu/cases")
@RequiredArgsConstructor
public class SiuCaseController {

    private final SiuCaseService service;
    private final SiuCaseQueryService queryService;

    @Operation(summary = "List SIU cases (optional status + assignedTo filters)")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "case summary list")})
    @GetMapping
    @RequiresPermission(Permissions.CLAIMS_SIU_VIEW)
    public Flux<SiuCaseSummaryResponse> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID assignedTo) {
        return queryService.findAll(status, assignedTo);
    }

    @Operation(summary = "Get one case with linked flags + notes")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "case detail"),
            @ApiResponse(responseCode = "404", description = "case not found")
    })
    @GetMapping("/{caseId}")
    @RequiresPermission(Permissions.CLAIMS_SIU_VIEW)
    public Mono<SiuCaseResponse> get(@PathVariable UUID caseId) {
        return queryService.findById(caseId);
    }

    @Operation(summary = "Start review — transition OPEN → UNDER_REVIEW")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "case moved to UNDER_REVIEW"),
            @ApiResponse(responseCode = "400", description = "illegal state transition")
    })
    @PostMapping("/{caseId}/start-review")
    @RequiresPermission(Permissions.CLAIMS_SIU_INVESTIGATE)
    public Mono<SiuCaseResponse> startReview(
            @PathVariable UUID caseId,
            @AuthenticationPrincipal Jwt jwt) {
        return service.startReview(caseId, jwt)
                .flatMap(kase -> queryService.findById(kase.getId()));
    }

    @Operation(summary = "Assign case — OPEN → ASSIGNED (four-eyes precondition)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "case assigned"),
            @ApiResponse(responseCode = "400", description = "illegal state transition"),
            @ApiResponse(responseCode = "403", description = "caller lacks claims:siu:assign")
    })
    @PostMapping("/{caseId}/assign")
    @RequiresPermission(Permissions.CLAIMS_SIU_ASSIGN)
    public Mono<SiuCaseResponse> assign(
            @PathVariable UUID caseId,
            @RequestBody AssignCaseRequest req,
            @AuthenticationPrincipal Jwt jwt) {
        return service.assign(caseId, req.assigneeId(), jwt)
                .flatMap(kase -> queryService.findById(kase.getId()));
    }

    @Operation(summary = "Start review from ASSIGNED — ASSIGNED → UNDER_REVIEW")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "case moved to UNDER_REVIEW"),
            @ApiResponse(responseCode = "400", description = "illegal state transition")
    })
    @PostMapping("/{caseId}/start-review-from-assigned")
    @RequiresPermission(Permissions.CLAIMS_SIU_INVESTIGATE)
    public Mono<SiuCaseResponse> startReviewFromAssigned(
            @PathVariable UUID caseId,
            @AuthenticationPrincipal Jwt jwt) {
        return service.startReviewFromAssigned(caseId, jwt)
                .flatMap(kase -> queryService.findById(kase.getId()));
    }

    @Operation(summary = "Propose non-dismissal closure — UNDER_REVIEW → PENDING_APPROVAL "
            + "(four-eyes gate — supervisor must approve via /approve-closure)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "case moved to PENDING_APPROVAL"),
            @ApiResponse(responseCode = "400", description = "missing fields, illegal state, or illegal outcome")
    })
    @PostMapping("/{caseId}/propose-closure")
    @RequiresPermission(Permissions.CLAIMS_SIU_INVESTIGATE)
    public Mono<SiuCaseResponse> proposeClosure(
            @PathVariable UUID caseId,
            @RequestBody ProposeClosureRequest req,
            @AuthenticationPrincipal Jwt jwt) {
        return service.proposeClosure(caseId, req.outcome(),
                        req.savedAmount(), req.savedCurrency(),
                        req.closureReason(), jwt)
                .flatMap(kase -> queryService.findById(kase.getId()));
    }

    @Operation(summary = "Approve pending closure — PENDING_APPROVAL → CLOSED_* "
            + "(four-eyes: approver must differ from proposer per FR6)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "case closed with proposed outcome"),
            @ApiResponse(responseCode = "400", description = "illegal state or four-eyes violation"),
            @ApiResponse(responseCode = "403", description = "caller lacks claims:siu:approve")
    })
    @PostMapping("/{caseId}/approve-closure")
    @RequiresPermission(Permissions.CLAIMS_SIU_APPROVE)
    public Mono<SiuCaseResponse> approveClosure(
            @PathVariable UUID caseId,
            @AuthenticationPrincipal Jwt jwt) {
        return service.approveClosure(caseId, jwt)
                .flatMap(kase -> queryService.findById(kase.getId()));
    }

    @Operation(summary = "Reject pending closure — PENDING_APPROVAL → UNDER_REVIEW "
            + "(sends the proposal back to the investigator)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "case sent back to UNDER_REVIEW"),
            @ApiResponse(responseCode = "400", description = "illegal state transition"),
            @ApiResponse(responseCode = "403", description = "caller lacks claims:siu:approve")
    })
    @PostMapping("/{caseId}/reject-closure")
    @RequiresPermission(Permissions.CLAIMS_SIU_APPROVE)
    public Mono<SiuCaseResponse> rejectClosure(
            @PathVariable UUID caseId,
            @RequestBody RejectClosureRequest req,
            @AuthenticationPrincipal Jwt jwt) {
        return service.rejectClosure(caseId, req.rejectionNote(), jwt)
                .flatMap(kase -> queryService.findById(kase.getId()));
    }

    @Operation(summary = "Reopen a closed case — CLOSED_* → UNDER_REVIEW via transient REOPENED note")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "case reopened; landed on UNDER_REVIEW"),
            @ApiResponse(responseCode = "400", description = "case is not in a CLOSED_* status"),
            @ApiResponse(responseCode = "403", description = "caller lacks claims:siu:reopen")
    })
    @PostMapping("/{caseId}/reopen")
    @RequiresPermission(Permissions.CLAIMS_SIU_REOPEN)
    public Mono<SiuCaseResponse> reopen(
            @PathVariable UUID caseId,
            @RequestBody ReopenCaseRequest req,
            @AuthenticationPrincipal Jwt jwt) {
        return service.reopen(caseId, req.reopenReason(), jwt)
                .flatMap(kase -> queryService.findById(kase.getId()));
    }

    @Operation(summary = "Close DISMISSED — savings fields must be null")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "case closed DISMISSED_FALSE_POSITIVE"),
            @ApiResponse(responseCode = "400", description = "illegal state transition")
    })
    @PostMapping("/{caseId}/close-dismissed")
    @RequiresPermission(Permissions.CLAIMS_SIU_INVESTIGATE)
    public Mono<SiuCaseResponse> closeDismissed(
            @PathVariable UUID caseId,
            @RequestBody CloseCaseRequest req,
            @AuthenticationPrincipal Jwt jwt) {
        return service.closeDismissed(caseId, req.closureReason(), jwt)
                .flatMap(kase -> queryService.findById(kase.getId()));
    }

    // ── Evidence + external referrals (§B Phase 7) ──────────────────────

    @Operation(summary = "Attach evidence to a case (file bytes live in file-service)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "evidence row persisted"),
            @ApiResponse(responseCode = "404", description = "case not found")
    })
    @PostMapping("/{caseId}/evidence")
    @RequiresPermission(Permissions.CLAIMS_SIU_INVESTIGATE)
    public Mono<SiuEvidenceResponse> addEvidence(
            @PathVariable UUID caseId,
            @RequestBody AddEvidenceRequest req,
            @AuthenticationPrincipal Jwt jwt) {
        return service.addEvidence(caseId, req.fileServiceRef(), req.description(),
                        req.evidenceType(), jwt)
                .map(SiuEvidenceResponse::from);
    }

    @Operation(summary = "Record an external referral (law enforcement / regulator / HR)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "referral row persisted"),
            @ApiResponse(responseCode = "403", description = "caller lacks claims:siu:refer"),
            @ApiResponse(responseCode = "404", description = "case not found")
    })
    @PostMapping("/{caseId}/referrals")
    @RequiresPermission(Permissions.CLAIMS_SIU_REFER)
    public Mono<SiuReferralResponse> addReferral(
            @PathVariable UUID caseId,
            @RequestBody AddReferralRequest req,
            @AuthenticationPrincipal Jwt jwt) {
        return service.addReferral(caseId, req.referralTo(), req.referralReference(), jwt)
                .map(SiuReferralResponse::from);
    }
}

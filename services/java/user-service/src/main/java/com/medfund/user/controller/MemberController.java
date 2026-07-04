package com.medfund.user.controller;

import com.medfund.shared.audit.AuditActor;
import com.medfund.user.dto.CreateMemberRequest;
import com.medfund.user.dto.CursorPage;
import com.medfund.user.dto.MemberResponse;
import com.medfund.user.dto.UpdateMemberRequest;
import com.medfund.user.service.MemberService;
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
import org.springframework.web.bind.annotation.RequestParam;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/members")
@Tag(name = "Members", description = "Member lifecycle management — enroll, activate, suspend, terminate")
@SecurityRequirement(name = "bearer-jwt")
public class MemberController {

    private final MemberService memberService;
    private final com.medfund.user.repository.MemberRepository memberRepository;

    public MemberController(MemberService memberService,
                             com.medfund.user.repository.MemberRepository memberRepository) {
        this.memberService = memberService;
        this.memberRepository = memberRepository;
    }

    /**
     * Lookup used by the arrears-escalation auto-reactivate sweep in
     * contributions-service. Returns every member currently in
     * {@code status = 'suspended'} whose {@code suspend_reason}
     * matches. Kept scoped to arrears-shaped queries (no free-form
     * status/reason combos) — new lookups will get their own endpoint.
     */
    @GetMapping("/suspended")
    @Operation(summary = "List members currently suspended for a given reason",
        description = "Internal lookup for auto-reactivation flows. Filter by suspend_reason " +
                      "(e.g. ARREARS_ESCALATION).")
    public Flux<MemberResponse> listSuspendedByReason(
            @RequestParam(name = "reason") String reason) {
        return memberRepository.findSuspendedByReason(reason).map(MemberResponse::from);
    }

    @GetMapping
    @Operation(summary = "List members with cursor pagination",
               description = "Pass cursor from previous response to get the next page. " +
                             "Use q for full-text search, status to filter by member status.")
    public Mono<CursorPage<MemberResponse>> findAll(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        return memberService.findPage(q, status, cursor, Math.min(limit, 100));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get member by ID")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Member found"),
        @ApiResponse(responseCode = "404", description = "Member not found")
    })
    public Mono<MemberResponse> findById(@PathVariable UUID id) {
        return memberService.findById(id).map(MemberResponse::from);
    }

    @GetMapping("/number/{memberNumber}")
    @Operation(summary = "Get member by member number")
    public Mono<MemberResponse> findByMemberNumber(@PathVariable String memberNumber) {
        return memberService.findByMemberNumber(memberNumber).map(MemberResponse::from);
    }

    @GetMapping("/group/{groupId}")
    @Operation(summary = "List members by group")
    public Flux<MemberResponse> findByGroupId(@PathVariable UUID groupId) {
        return memberService.findByGroupId(groupId).map(MemberResponse::from);
    }

    @GetMapping("/status/{status}")
    @Operation(summary = "List members by status")
    public Flux<MemberResponse> findByStatus(@PathVariable String status) {
        return memberService.findByStatus(status).map(MemberResponse::from);
    }

    @GetMapping("/search")
    @Operation(summary = "Search members by name or member number")
    public Flux<MemberResponse> search(@RequestParam String q) {
        return memberService.search(q).map(MemberResponse::from);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Enroll a new member",
        description = "Creates member, generates member number, syncs to Keycloak, publishes enrollment event")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Member enrolled"),
        @ApiResponse(responseCode = "400", description = "Validation error")
    })
    public Mono<MemberResponse> enroll(@Valid @RequestBody CreateMemberRequest request, @AuthenticationPrincipal Jwt jwt) {
        return memberService.enroll(request, AuditActor.id(jwt), AuditActor.email(jwt)).map(MemberResponse::from);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update member details")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Member updated"),
        @ApiResponse(responseCode = "404", description = "Member not found")
    })
    public Mono<MemberResponse> update(@PathVariable UUID id,
                                        @Valid @RequestBody UpdateMemberRequest request,
                                        @AuthenticationPrincipal Jwt jwt) {
        return memberService.update(id, request, AuditActor.id(jwt), AuditActor.email(jwt)).map(MemberResponse::from);
    }

    @PostMapping("/{id}/activate")
    @Operation(summary = "Activate member")
    public Mono<MemberResponse> activate(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return memberService.activate(id, AuditActor.id(jwt), AuditActor.email(jwt)).map(MemberResponse::from);
    }

    @PostMapping("/{id}/suspend")
    @Operation(summary = "Suspend member", description = "Suspends member and disables Keycloak account")
    public Mono<MemberResponse> suspend(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return memberService.suspend(id, AuditActor.id(jwt), AuditActor.email(jwt)).map(MemberResponse::from);
    }

    @PostMapping("/{id}/terminate")
    @Operation(summary = "Terminate member", description = "Terminates member, sets termination date, disables Keycloak account")
    public Mono<MemberResponse> terminate(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return memberService.terminate(id, AuditActor.id(jwt), AuditActor.email(jwt)).map(MemberResponse::from);
    }

    /**
     * Idempotent status-change action. Accepts optional {@code effectiveDate}
     * — omit or set to today for an immediate flip; set in the future to
     * schedule (the daily SCHEDULED_STATUS_ROLL job picks it up on that
     * day). Actions: {@code activate | suspend | terminate | deactivate |
     * reactivate}. {@code reactivate} is an alias for {@code activate}
     * used by the arrears escalation flow so audit trails carry the
     * "why" distinct from a manual activation.
     */
    @PostMapping("/{id}/actions/{action}")
    @Operation(summary = "Schedule or apply a status change on a member",
        description = "One idempotent endpoint replacing the four legacy status endpoints. Accepts optional " +
                      "effectiveDate + reason. Immediate when effectiveDate is null / today / past; scheduled " +
                      "when in the future. reason is threaded through to the lifecycle event and audit.")
    public Mono<MemberResponse> action(@PathVariable UUID id,
                                        @PathVariable String action,
                                        @Valid @RequestBody(required = false) MemberActionRequest request,
                                        @AuthenticationPrincipal Jwt jwt) {
        java.time.LocalDate effectiveDate = request != null ? request.effectiveDate() : null;
        String reason = request != null ? request.reason() : null;
        String actorId = AuditActor.id(jwt);
        String actorEmail = AuditActor.email(jwt);
        Mono<com.medfund.user.entity.Member> op = switch (action.toLowerCase()) {
            case "activate", "reactivate" ->
                memberService.activate(id, effectiveDate, reason, actorId, actorEmail);
            case "suspend" -> memberService.suspend(id, effectiveDate, reason, actorId, actorEmail);
            case "terminate" -> memberService.terminate(id, effectiveDate, reason, actorId, actorEmail);
            case "deactivate" -> memberService.deactivate(id, effectiveDate, reason, actorId, actorEmail);
            default -> Mono.error(new IllegalArgumentException(
                "Unknown action '" + action + "' — expected activate / suspend / terminate / deactivate / reactivate"));
        };
        return op.map(MemberResponse::from);
    }

    /**
     * Body for {@link #action}. Both fields optional. If provided,
     * {@code effectiveDate} in ISO-8601 (YYYY-MM-DD); {@code reason} is
     * free-text carried onto the lifecycle event and audit row so a
     * downstream consumer can distinguish OPERATOR from
     * ARREARS_ESCALATION.
     */
    public record MemberActionRequest(java.time.LocalDate effectiveDate, String reason) {}

    @PostMapping("/{id}/clear-billing-override")
    @Operation(summary = "Clear the member's per-person pricing override",
        description = "Nulls billing_override_amount, reason, and effective_from so billing falls back to the " +
                      "age-group price. Distinct from update so the PATCH semantics on update can stay " +
                      "'null = no change' without ambiguity about how to remove an override.")
    public Mono<MemberResponse> clearBillingOverride(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return memberService.clearBillingOverride(id, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(MemberResponse::from);
    }
}

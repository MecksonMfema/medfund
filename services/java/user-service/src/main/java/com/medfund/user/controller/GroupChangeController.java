package com.medfund.user.controller;

import com.medfund.shared.audit.AuditActor;
import com.medfund.user.dto.GroupChangeRequest;
import com.medfund.user.dto.GroupChangeResponse;
import com.medfund.user.service.GroupChangeService;
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

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/members/{memberId}/group-changes")
@Tag(name = "Group changes",
     description = "Request or approve moving a member between groups. Back-dated requests apply immediately and post arrears/rebate on the ledger.")
@SecurityRequirement(name = "bearer-jwt")
public class GroupChangeController {

    private final GroupChangeService service;

    public GroupChangeController(GroupChangeService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Book a group change",
        description = "Forward-dated → stashed as PENDING for approval. Back-dated → applied immediately and MEMBER_CHANGED event fires with backdated=true so contributions-service posts arrears or rebate.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Change booked (either PENDING or APPLIED)"),
        @ApiResponse(responseCode = "422", description = "Target group invalid or a live request already exists")
    })
    public Mono<GroupChangeResponse> request(@PathVariable UUID memberId,
                                              @Valid @RequestBody GroupChangeRequest body,
                                              @AuthenticationPrincipal Jwt jwt) {
        return service.request(memberId, body, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(GroupChangeResponse::from);
    }

    @GetMapping
    @Operation(summary = "List group changes for a member")
    public Flux<GroupChangeResponse> list(@PathVariable UUID memberId) {
        return service.findByMemberId(memberId).map(GroupChangeResponse::from);
    }

    @PostMapping("/{id}/approve")
    @Operation(summary = "Approve a PENDING group change")
    public Mono<GroupChangeResponse> approve(@PathVariable UUID memberId,
                                              @PathVariable UUID id,
                                              @AuthenticationPrincipal Jwt jwt) {
        return service.approve(id, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(GroupChangeResponse::from);
    }

    @PostMapping("/{id}/reject")
    @Operation(summary = "Reject a PENDING or APPROVED group change")
    public Mono<GroupChangeResponse> reject(@PathVariable UUID memberId,
                                             @PathVariable UUID id,
                                             @RequestBody(required = false) Map<String, String> body,
                                             @AuthenticationPrincipal Jwt jwt) {
        String reason = body == null ? null : body.get("reason");
        return service.reject(id, reason, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(GroupChangeResponse::from);
    }

    @PostMapping("/{id}/apply")
    @Operation(summary = "Force-apply an APPROVED group change ahead of the scheduled sweep",
        description = "Normally the daily scheduled executor runs this on the effective date. Exposed so operations can flip a stuck row manually.")
    public Mono<GroupChangeResponse> apply(@PathVariable UUID memberId,
                                            @PathVariable UUID id,
                                            @AuthenticationPrincipal Jwt jwt) {
        return service.apply(id, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(GroupChangeResponse::from);
    }
}

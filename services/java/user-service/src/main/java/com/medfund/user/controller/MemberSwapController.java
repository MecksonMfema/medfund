package com.medfund.user.controller;

import com.medfund.shared.audit.AuditActor;
import com.medfund.user.dto.MemberSwapRequest;
import com.medfund.user.dto.MemberSwapResponse;
import com.medfund.user.service.MemberSwapService;
import io.swagger.v3.oas.annotations.Operation;
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
@RequestMapping("/api/v1/members/{memberId}/swaps")
@Tag(name = "Member/Dependant swaps",
     description = "Promote a dependant to principal and demote the current principal to a dependant. Back-dated requests apply immediately.")
@SecurityRequirement(name = "bearer-jwt")
public class MemberSwapController {

    private final MemberSwapService service;

    public MemberSwapController(MemberSwapService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Book a role swap between a member and one of their dependants")
    public Mono<MemberSwapResponse> request(@PathVariable UUID memberId,
                                             @Valid @RequestBody MemberSwapRequest body,
                                             @AuthenticationPrincipal Jwt jwt) {
        return service.request(memberId, body, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(MemberSwapResponse::from);
    }

    @GetMapping
    @Operation(summary = "List swaps involving this member")
    public Flux<MemberSwapResponse> list(@PathVariable UUID memberId) {
        return service.findByMemberId(memberId).map(MemberSwapResponse::from);
    }

    @PostMapping("/{id}/approve")
    @Operation(summary = "Approve a PENDING swap")
    public Mono<MemberSwapResponse> approve(@PathVariable UUID memberId,
                                             @PathVariable UUID id,
                                             @AuthenticationPrincipal Jwt jwt) {
        return service.approve(id, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(MemberSwapResponse::from);
    }

    @PostMapping("/{id}/reject")
    @Operation(summary = "Reject a PENDING or APPROVED swap")
    public Mono<MemberSwapResponse> reject(@PathVariable UUID memberId,
                                            @PathVariable UUID id,
                                            @RequestBody(required = false) Map<String, String> body,
                                            @AuthenticationPrincipal Jwt jwt) {
        String reason = body == null ? null : body.get("reason");
        return service.reject(id, reason, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(MemberSwapResponse::from);
    }

    @PostMapping("/{id}/apply")
    @Operation(summary = "Force-apply an APPROVED swap")
    public Mono<MemberSwapResponse> apply(@PathVariable UUID memberId,
                                           @PathVariable UUID id,
                                           @AuthenticationPrincipal Jwt jwt) {
        return service.apply(id, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(MemberSwapResponse::from);
    }
}

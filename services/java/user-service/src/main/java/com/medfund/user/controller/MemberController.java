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

    public MemberController(MemberService memberService) {
        this.memberService = memberService;
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
}

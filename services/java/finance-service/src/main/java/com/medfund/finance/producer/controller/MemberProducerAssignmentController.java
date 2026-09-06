package com.medfund.finance.producer.controller;

import com.medfund.finance.producer.dto.AssignMemberRequest;
import com.medfund.finance.producer.dto.AssignmentResponse;
import com.medfund.finance.producer.service.MemberProducerAssignmentService;
import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.security.Permissions;
import com.medfund.shared.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Member-side view of the member ↔ producer assignment: read history, read
 * current, assign (creates + closes prior), close current (no successor).
 * The producer-side view (open assignments for a producer, paged) lives at
 * {@link ProducerAssignmentsController}.
 */
@RestController
@RequestMapping("/api/v1/members/{memberId}/producer-assignment")
@RequiredArgsConstructor
@Tag(name = "Producers - Member Assignment",
     description = "Time-slice assignment of a member to a producer. At most one open row per member.")
@SecurityRequirement(name = "bearer-jwt")
public class MemberProducerAssignmentController {

    private final MemberProducerAssignmentService service;

    @GetMapping
    @RequiresPermission(Permissions.PRODUCER_VIEW)
    @Operation(summary = "Get the currently-open assignment for a member (204 if none).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Open assignment"),
            @ApiResponse(responseCode = "204", description = "No open assignment")
    })
    public Mono<ResponseEntity<AssignmentResponse>> current(@PathVariable UUID memberId) {
        return service.currentFor(memberId)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.noContent().build());
    }

    @GetMapping("/history")
    @RequiresPermission(Permissions.PRODUCER_VIEW)
    @Operation(summary = "Full assignment history for a member, newest first.")
    public Flux<AssignmentResponse> history(@PathVariable UUID memberId) {
        return service.historyFor(memberId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permissions.PRODUCER_MANAGE)
    @Operation(summary = "Assign a member to a producer",
            description = "Closes any currently-open assignment (effective_to = day before new "
                        + "effective_from) and inserts a new open row. effectiveFrom snaps to "
                        + "1st-of-month.")
    public Mono<AssignmentResponse> assign(@PathVariable UUID memberId,
                                           @Valid @RequestBody AssignMemberRequest body,
                                           @AuthenticationPrincipal Jwt jwt) {
        return service.assign(memberId, body, AuditActor.id(jwt), AuditActor.email(jwt));
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(Permissions.PRODUCER_MANAGE)
    @Operation(summary = "Close the currently-open assignment (no successor)",
            description = "Commission calc during the gap warns + skips. effective_to snaps to "
                        + "last-day-of-month.")
    public Mono<Void> closeCurrent(@PathVariable UUID memberId,
                                   @AuthenticationPrincipal Jwt jwt) {
        return service.closeCurrent(memberId, AuditActor.id(jwt), AuditActor.email(jwt));
    }
}

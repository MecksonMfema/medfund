package com.medfund.user.controller;

import com.medfund.shared.audit.AuditActor;
import com.medfund.user.dto.CreateGroupRequest;
import com.medfund.user.dto.GroupResponse;
import com.medfund.user.dto.UpdateGroupRequest;
import com.medfund.user.service.GroupService;
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

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/groups")
@Tag(name = "Groups", description = "Corporate group management")
@SecurityRequirement(name = "bearer-jwt")
public class GroupController {

    private final GroupService groupService;
    private final com.medfund.user.repository.GroupRepository groupRepository;

    public GroupController(GroupService groupService,
                            com.medfund.user.repository.GroupRepository groupRepository) {
        this.groupService = groupService;
        this.groupRepository = groupRepository;
    }

    /**
     * Lookup used by the arrears-escalation auto-reactivate sweep in
     * contributions-service. Returns every group currently in
     * {@code status = 'suspended'} whose {@code suspend_reason}
     * matches. Same pattern as
     * {@code MemberController.listSuspendedByReason}.
     */
    @GetMapping("/suspended")
    @Operation(summary = "List groups currently suspended for a given reason",
        description = "Internal lookup for auto-reactivation flows.")
    public Flux<GroupResponse> listSuspendedByReason(
            @RequestParam(name = "reason") String reason) {
        return groupRepository.findSuspendedByReason(reason).map(GroupResponse::from);
    }

    @GetMapping
    @Operation(summary = "List all groups")
    public Flux<GroupResponse> findAll() {
        return groupService.findAll().map(GroupResponse::from);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get group by ID")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Group found"),
        @ApiResponse(responseCode = "404", description = "Group not found")
    })
    public Mono<GroupResponse> findById(@PathVariable UUID id) {
        return groupService.findById(id).map(GroupResponse::from);
    }

    @GetMapping("/status/{status}")
    @Operation(summary = "List groups by status")
    public Flux<GroupResponse> findByStatus(@PathVariable String status) {
        return groupService.findByStatus(status).map(GroupResponse::from);
    }

    @GetMapping("/search")
    @Operation(summary = "Search groups by name")
    public Flux<GroupResponse> search(@RequestParam String q) {
        return groupService.search(q).map(GroupResponse::from);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new group")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Group created"),
        @ApiResponse(responseCode = "400", description = "Validation error")
    })
    public Mono<GroupResponse> create(@Valid @RequestBody CreateGroupRequest request, @AuthenticationPrincipal Jwt jwt) {
        return groupService.create(request, AuditActor.id(jwt), AuditActor.email(jwt)).map(GroupResponse::from);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update group details")
    public Mono<GroupResponse> update(@PathVariable UUID id,
                                       @Valid @RequestBody UpdateGroupRequest request,
                                       @AuthenticationPrincipal Jwt jwt) {
        return groupService.update(id, request, AuditActor.id(jwt), AuditActor.email(jwt)).map(GroupResponse::from);
    }

    @PostMapping("/{id}/suspend")
    @Operation(summary = "Suspend group")
    public Mono<GroupResponse> suspend(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return groupService.suspend(id, AuditActor.id(jwt), AuditActor.email(jwt)).map(GroupResponse::from);
    }

    /**
     * Idempotent status-change endpoint for groups. Mirrors
     * {@code MemberController.action}. When the group cascades
     * to {@code deactivated} or {@code terminated} the service walks its
     * members and applies the same status to any in {@code active} or
     * {@code suspended} — see {@code GroupService.cascadeToMembers}.
     */
    @PostMapping("/{id}/actions/{action}")
    @Operation(summary = "Schedule or apply a group-level status change",
        description = "Actions: activate / suspend / terminate / deactivate / reactivate. Optional " +
                      "effectiveDate + reason; future dates go through the daily SCHEDULED_STATUS_ROLL. " +
                      "deactivate / terminate cascade to member statuses on the same run.")
    public Mono<GroupResponse> action(@PathVariable UUID id,
                                       @PathVariable String action,
                                       @Valid @RequestBody(required = false) GroupActionRequest request,
                                       @AuthenticationPrincipal Jwt jwt) {
        java.time.LocalDate effectiveDate = request != null ? request.effectiveDate() : null;
        String reason = request != null ? request.reason() : null;
        String actorId = AuditActor.id(jwt);
        String actorEmail = AuditActor.email(jwt);
        Mono<com.medfund.user.entity.Group> op = switch (action.toLowerCase()) {
            case "activate", "reactivate" ->
                groupService.activate(id, effectiveDate, reason, actorId, actorEmail);
            case "suspend" -> groupService.suspend(id, effectiveDate, reason, actorId, actorEmail);
            case "terminate" -> groupService.terminate(id, effectiveDate, reason, actorId, actorEmail);
            case "deactivate" -> groupService.deactivate(id, effectiveDate, reason, actorId, actorEmail);
            default -> Mono.error(new IllegalArgumentException(
                "Unknown action '" + action + "' — expected activate / suspend / terminate / deactivate / reactivate"));
        };
        return op.map(GroupResponse::from);
    }

    /** Body for {@link #action}. Both fields optional. */
    public record GroupActionRequest(java.time.LocalDate effectiveDate, String reason) {}
}

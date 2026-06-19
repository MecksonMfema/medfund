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

    public GroupController(GroupService groupService) {
        this.groupService = groupService;
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
}

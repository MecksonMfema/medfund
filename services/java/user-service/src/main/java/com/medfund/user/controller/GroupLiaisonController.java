package com.medfund.user.controller;

import com.medfund.shared.audit.AuditActor;
import com.medfund.user.dto.CreateGroupLiaisonRequest;
import com.medfund.user.dto.GroupLiaisonResponse;
import com.medfund.user.service.GroupLiaisonService;
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
@RequestMapping("/api/v1/group-liaisons")
@Tag(name = "Group liaisons",
     description = "Manage 'pure' group liaisons — people who manage a group's invoices via the group portal without being a member or staff user")
@SecurityRequirement(name = "bearer-jwt")
public class GroupLiaisonController {

    private final GroupLiaisonService service;

    public GroupLiaisonController(GroupLiaisonService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Search group liaisons by name or email",
               description = "Used by the operational portal's liaison picker. Returns a flat list capped at limit.")
    public Flux<GroupLiaisonResponse> search(@RequestParam(required = false, defaultValue = "") String q,
                                             @RequestParam(defaultValue = "10") int limit) {
        return service.search(q, limit).map(GroupLiaisonResponse::from);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a group liaison by ID")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Liaison found"),
        @ApiResponse(responseCode = "404", description = "Liaison not found")
    })
    public Mono<GroupLiaisonResponse> findById(@PathVariable UUID id) {
        return service.findById(id).map(GroupLiaisonResponse::from);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a group liaison",
               description = "Inserts the record, provisions a Keycloak user in the tenant realm with the group_liaison realm role, and sends an invite email.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Liaison created"),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "409", description = "A liaison with this email already exists")
    })
    public Mono<GroupLiaisonResponse> create(@Valid @RequestBody CreateGroupLiaisonRequest request, @AuthenticationPrincipal Jwt jwt) {
        return service.create(request, AuditActor.id(jwt), AuditActor.email(jwt)).map(GroupLiaisonResponse::from);
    }
}

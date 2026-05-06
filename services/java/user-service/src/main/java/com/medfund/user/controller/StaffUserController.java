package com.medfund.user.controller;

import com.medfund.user.dto.CreateStaffUserRequest;
import com.medfund.user.dto.CursorPage;
import com.medfund.user.dto.StaffUserResponse;
import com.medfund.user.dto.UpdateStaffUserRequest;
import com.medfund.user.service.StaffUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
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
@RequestMapping("/api/v1/staff-users")
@Tag(name = "Staff Users", description = "Platform staff user management — creates users in DB and syncs to Keycloak")
@SecurityRequirement(name = "bearer-jwt")
public class StaffUserController {

    private final StaffUserService service;
    private final org.springframework.r2dbc.core.DatabaseClient db;

    public StaffUserController(StaffUserService service, org.springframework.r2dbc.core.DatabaseClient db) {
        this.service = service;
        this.db = db;
    }

    /** Debug: raw SQL count to verify the DB connection and query work. Remove once diagnosed. */
    @GetMapping("/debug-count")
    public reactor.core.publisher.Mono<java.util.Map<String, Object>> debugCount() {
        return db.sql("SELECT COUNT(*) AS cnt FROM public.staff_users WHERE tenant_id IS NULL")
                .map(row -> {
                    Long cnt = row.get("cnt", Long.class);
                    return java.util.Map.<String, Object>of("count", cnt != null ? cnt : -1);
                })
                .one()
                .defaultIfEmpty(java.util.Map.of("count", -1, "note", "no result"));
    }

    @GetMapping
    @Operation(summary = "List staff users with cursor pagination",
               description = "Tenant portal: send X-Tenant-ID header to get that tenant's staff. " +
                             "Platform admin: omit header to get super_admin users only. " +
                             "Use q for full-text search. Pass cursor from previous response for next page.")
    public Mono<CursorPage<StaffUserResponse>> findAll(
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantHeader,
            @RequestParam(required = false) UUID tenantId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {

        // Resolve tenant from header (tenant portal) or query param (platform admin cross-tenant).
        // Rules:
        //  - Valid UUID                  → filter by that tenant
        //  - No header / blank / non-UUID → no tenant context = platform staff list
        //                                   (super_admin users, WHERE tenant_id IS NULL)
        if (tenantHeader != null && !tenantHeader.isBlank()) {
            try {
                UUID parsed = UUID.fromString(tenantHeader);
                return service.findPage(parsed, q, cursor, Math.min(limit, 100));
            } catch (IllegalArgumentException ignored) {
                // fall through to platform path
            }
        }

        // No tenant context — use optional query param (platform admin explicit cross-tenant view)
        return service.findPage(tenantId, q, cursor, Math.min(limit, 100));
    }

    @GetMapping("/{id:[0-9a-fA-F-]+}")
    @Operation(summary = "Get staff user by ID")
    @ApiResponse(responseCode = "200", description = "User found")
    @ApiResponse(responseCode = "404", description = "User not found")
    public Mono<StaffUserResponse> findById(
            @Parameter(description = "Staff user UUID") @PathVariable UUID id) {
        return service.findById(id).map(StaffUserResponse::from);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a staff user",
               description = "Creates the user in PostgreSQL and syncs to the medfund-platform Keycloak realm. " +
                             "The user will receive an email to set their password.")
    @ApiResponse(responseCode = "201", description = "User created")
    @ApiResponse(responseCode = "409", description = "Email already in use")
    public Mono<StaffUserResponse> create(
            @Valid @RequestBody CreateStaffUserRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        String actorId    = jwt != null ? jwt.getSubject()          : null;
        String actorEmail = jwt != null ? resolveActorEmail(jwt)     : null;
        return service.create(request, actorId, actorEmail).map(StaffUserResponse::from);
    }

    @PutMapping("/{id:[0-9a-fA-F-]+}")
    @Operation(summary = "Update a staff user")
    public Mono<StaffUserResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateStaffUserRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        String actorId    = jwt != null ? jwt.getSubject()      : null;
        String actorEmail = jwt != null ? resolveActorEmail(jwt) : null;
        return service.update(id, request, actorId, actorEmail).map(StaffUserResponse::from);
    }

    @PostMapping("/{id:[0-9a-fA-F-]+}/suspend")
    @Operation(summary = "Suspend a staff user", description = "Disables the account in both DB and Keycloak.")
    public Mono<StaffUserResponse> suspend(
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt) {
        String actorId    = jwt != null ? jwt.getSubject()      : null;
        String actorEmail = jwt != null ? resolveActorEmail(jwt) : null;
        return service.suspend(id, actorId, actorEmail).map(StaffUserResponse::from);
    }

    @PostMapping("/{id:[0-9a-fA-F-]+}/activate")
    @Operation(summary = "Activate a suspended staff user", description = "Re-enables the account in both DB and Keycloak.")
    public Mono<StaffUserResponse> activate(
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt) {
        String actorId    = jwt != null ? jwt.getSubject()      : null;
        String actorEmail = jwt != null ? resolveActorEmail(jwt) : null;
        return service.activate(id, actorId, actorEmail).map(StaffUserResponse::from);
    }

    @PostMapping("/{id:[0-9a-fA-F-]+}/resend-invite")
    @Operation(summary = "Resend invite email",
               description = "Re-triggers Keycloak's execute-actions-email so the user gets a fresh password-setup link.")
    @ApiResponse(responseCode = "200", description = "Invite email re-sent")
    @ApiResponse(responseCode = "404", description = "User not found")
    public Mono<StaffUserResponse> resendInvite(@PathVariable UUID id) {
        return service.resendInvite(id).map(StaffUserResponse::from);
    }

    @GetMapping("/by-keycloak-id/{keycloakUserId}")
    @Operation(summary = "Get staff user by Keycloak user id",
               description = "Resolves the JWT subject (audit-event actorId) to a staff user record.")
    @ApiResponse(responseCode = "200", description = "User found")
    @ApiResponse(responseCode = "404", description = "User not found for the given keycloakUserId")
    public Mono<StaffUserResponse> findByKeycloakUserId(
            @Parameter(description = "Keycloak user UUID (JWT sub)") @PathVariable String keycloakUserId) {
        return service.findByKeycloakUserId(keycloakUserId).map(StaffUserResponse::from);
    }

    @GetMapping("/invitations")
    @Operation(summary = "List pending invitations",
               description = "All staff users with status='invited' — both platform and tenant-scoped. " +
                             "Pass tenantId to filter to a single tenant.")
    public reactor.core.publisher.Flux<StaffUserResponse> invitations(
            @RequestParam(required = false) UUID tenantId) {
        return service.findByStatus("invited", tenantId).map(StaffUserResponse::from);
    }

    @DeleteMapping("/{id:[0-9a-fA-F-]+}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a staff user", description = "Removes the user from DB and disables in Keycloak.")
    public Mono<Void> delete(
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt) {
        String actorId    = jwt != null ? jwt.getSubject()      : null;
        String actorEmail = jwt != null ? resolveActorEmail(jwt) : null;
        return service.delete(id, actorId, actorEmail);
    }

    /** Reads email from the JWT, falling back to preferred_username. */
    private static String resolveActorEmail(Jwt jwt) {
        String email = jwt.getClaimAsString("email");
        return email != null ? email : jwt.getClaimAsString("preferred_username");
    }
}

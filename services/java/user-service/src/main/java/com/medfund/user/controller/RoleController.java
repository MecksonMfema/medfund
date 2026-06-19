package com.medfund.user.controller;

import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.security.Permissions;
import com.medfund.user.dto.AssignRoleRequest;
import com.medfund.user.dto.CreateRoleRequest;
import com.medfund.user.dto.PermissionCatalogueResponse;
import com.medfund.user.dto.ReplacePermissionsRequest;
import com.medfund.user.dto.RoleResponse;
import com.medfund.user.dto.UpdateRoleRequest;
import com.medfund.user.entity.Role;
import com.medfund.user.entity.UserRole;
import com.medfund.user.service.RoleService;
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

import java.security.Principal;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/roles")
@Tag(name = "Roles & Permissions", description = "Role/permission management and user role assignment")
@SecurityRequirement(name = "bearer-jwt")
public class RoleController {

    private final RoleService roleService;

    public RoleController(RoleService roleService) {
        this.roleService = roleService;
    }

    @GetMapping
    @Operation(summary = "List all roles")
    public Flux<Role> findAll() {
        return roleService.findAll();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get role by ID with permissions")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Role found"),
        @ApiResponse(responseCode = "404", description = "Role not found")
    })
    public Mono<RoleResponse> findById(@PathVariable UUID id) {
        return roleService.findByIdWithPermissions(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new role with permissions")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Role created"),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "409", description = "Role name already exists")
    })
    public Mono<Role> create(@Valid @RequestBody CreateRoleRequest request, @AuthenticationPrincipal Jwt jwt) {
        return roleService.create(request, AuditActor.id(jwt), AuditActor.email(jwt));
    }

    @GetMapping("/user/{userId}")
    @Operation(summary = "List roles assigned to a user")
    public Flux<UserRole> findUserRoles(@PathVariable UUID userId) {
        return roleService.findUserRoles(userId);
    }

    @GetMapping("/{roleId}/members")
    @Operation(summary = "List user_roles rows holding a given role",
            description = "Drives the role-management 'Members' drawer in the tenant settings UI.")
    @ApiResponse(responseCode = "200", description = "Member list returned")
    public Flux<UserRole> findRoleMembers(@PathVariable UUID roleId) {
        return roleService.findRoleMembers(roleId);
    }

    @PostMapping("/assign")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Assign a role to a user", description = "Assigns role and syncs with Keycloak realm roles")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Role assigned"),
        @ApiResponse(responseCode = "400", description = "Validation error")
    })
    public Mono<UserRole> assignRole(@Valid @RequestBody AssignRoleRequest request, @AuthenticationPrincipal Jwt jwt) {
        return roleService.assignRole(request, AuditActor.id(jwt), AuditActor.email(jwt));
    }

    @DeleteMapping("/user/{userId}/role/{roleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Revoke a role from a user")
    public Mono<Void> revokeRole(@PathVariable UUID userId, @PathVariable UUID roleId, @AuthenticationPrincipal Jwt jwt) {
        return roleService.revokeRole(userId, roleId, AuditActor.id(jwt), AuditActor.email(jwt));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update role metadata", description = "Updates display name and description. Role name is immutable.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Role updated"),
        @ApiResponse(responseCode = "400", description = "System role cannot be edited"),
        @ApiResponse(responseCode = "404", description = "Role not found")
    })
    public Mono<Role> update(@PathVariable UUID id, @Valid @RequestBody UpdateRoleRequest request, Principal principal) {
        return roleService.update(id, request, principal.getName());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a role", description = "Cascades to user_roles. System roles cannot be deleted.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Role deleted"),
        @ApiResponse(responseCode = "400", description = "System role cannot be deleted"),
        @ApiResponse(responseCode = "404", description = "Role not found")
    })
    public Mono<Void> delete(@PathVariable UUID id, Principal principal) {
        return roleService.delete(id, principal.getName());
    }

    @PutMapping("/{id}/permissions")
    @Operation(summary = "Replace a role's permission set",
            description = "Atomically wipes existing permissions and inserts the supplied list. " +
                    "Every key is validated against the canonical catalogue — unknown keys reject with 400.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Permissions replaced"),
        @ApiResponse(responseCode = "400", description = "Unknown permission key"),
        @ApiResponse(responseCode = "404", description = "Role not found")
    })
    public Mono<RoleResponse> replacePermissions(@PathVariable UUID id,
                                                  @Valid @RequestBody ReplacePermissionsRequest request,
                                                  Principal principal) {
        return roleService.replacePermissions(id, request, principal.getName());
    }
}

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Roles & Permissions", description = "Permission catalogue and self-service endpoints")
@SecurityRequirement(name = "bearer-jwt")
class PermissionController {

    private final RoleService roleService;

    PermissionController(RoleService roleService) {
        this.roleService = roleService;
    }

    @GetMapping("/permissions/catalogue")
    @Operation(summary = "Get the canonical permission catalogue",
            description = "Returns every permission the platform recognises, grouped by domain. " +
                    "Drives the role-editor UI and is the validation set for role permission edits.")
    @ApiResponse(responseCode = "200", description = "Catalogue returned")
    public Mono<PermissionCatalogueResponse> catalogue() {
        return Mono.just(roleService.getCatalogue());
    }

    @GetMapping("/me/permissions")
    @Operation(summary = "Get the calling user's effective permissions",
            description = "Resolves the union of permissions across every role assigned to the " +
                    "caller. Cached server-side; the cache invalidates within 60s of any role " +
                    "change (or sooner via Kafka invalidation events). " +
                    "Platform super_admins receive the full catalogue regardless of tenant " +
                    "since they manage every tenant.")
    @ApiResponse(responseCode = "200", description = "Permission set returned")
    @SuppressWarnings("unchecked")
    public Mono<Set<String>> myPermissions(@AuthenticationPrincipal Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null) {
            return Mono.just(Set.of());
        }
        // Platform super_admin sees everything operationally — they're the only
        // role that legitimately browses every tenant's portal, and gating
        // their access via the per-tenant role tables would be circular
        // (they aren't a tenant user).
        Object claim = jwt.getClaim("realm_access");
        if (claim instanceof java.util.Map<?, ?> realmAccess) {
            Object rolesObj = realmAccess.get("roles");
            if (rolesObj instanceof java.util.List<?> roles && roles.contains("super_admin")) {
                return Mono.just(Permissions.ALL);
            }
        }
        UUID userId;
        try {
            userId = UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException e) {
            return Mono.just(Set.of());
        }
        return roleService.getUserPermissions(userId);
    }
}

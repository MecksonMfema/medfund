package com.medfund.user.service;

import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.security.PermissionCatalogue;
import com.medfund.shared.security.Permissions;
import com.medfund.shared.tenant.TenantContext;
import com.medfund.user.dto.AssignRoleRequest;
import com.medfund.user.dto.CreateRoleRequest;
import com.medfund.user.dto.PermissionCatalogueResponse;
import com.medfund.user.dto.ReplacePermissionsRequest;
import com.medfund.user.dto.RoleResponse;
import com.medfund.user.dto.UpdateRoleRequest;
import com.medfund.user.entity.Role;
import com.medfund.user.entity.RolePermission;
import com.medfund.user.entity.UserRole;
import com.medfund.user.exception.DuplicateRoleException;
import com.medfund.user.exception.RoleNotFoundException;
import com.medfund.user.repository.RolePermissionRepository;
import com.medfund.user.repository.RoleRepository;
import com.medfund.user.repository.StaffUserRepository;
import com.medfund.user.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RoleService {

    private final RoleRepository roleRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final UserRoleRepository userRoleRepository;
    private final StaffUserRepository staffUserRepository;
    private final R2dbcEntityTemplate r2dbcTemplate;
    private final AuditPublisher auditPublisher;
    private final UserEventPublisher eventPublisher;
    private final KeycloakSyncService keycloakSyncService;

    public Flux<Role> findAll() {
        return roleRepository.findAllOrderByName();
    }

    public Mono<RoleResponse> findByIdWithPermissions(UUID id) {
        return roleRepository.findById(id)
            .switchIfEmpty(Mono.error(new RoleNotFoundException(id)))
            .flatMap(role -> rolePermissionRepository.findByRoleId(role.getId())
                .map(rp -> new RoleResponse.PermissionResponse(rp.getId(), rp.getPermission(), rp.getAccessLevel()))
                .collectList()
                .map(perms -> RoleResponse.from(role, perms)));
    }

    public Flux<UserRole> findUserRoles(UUID userId) {
        return userRoleRepository.findByUserId(userId);
    }

    /** Every user_roles row holding the given role — drives the Members drawer. */
    public Flux<UserRole> findRoleMembers(UUID roleId) {
        return userRoleRepository.findByRoleId(roleId);
    }

    @Transactional
    public Mono<Role> create(CreateRoleRequest request, String actorId) {
        // Block unknown permission keys at the door — privilege-escalation guard.
        if (request.permissions() != null) {
            for (var pe : request.permissions()) {
                if (!Permissions.ALL.contains(pe.permission())) {
                    return Mono.error(new IllegalArgumentException(
                            "Unknown permission key: " + pe.permission()));
                }
            }
        }
        return roleRepository.existsByName(request.name())
            .flatMap(exists -> {
                if (exists) return Mono.error(new DuplicateRoleException(request.name()));

                var role = new Role();
                // id NOT set — let PostgreSQL generate via DEFAULT gen_random_uuid()
                role.setName(request.name());
                role.setDisplayName(request.displayName());
                role.setDescription(request.description());
                role.setIsSystem(false);
                role.setKeycloakRoleName(slugify(request.name()));

                return r2dbcTemplate.insert(role)
                    .flatMap(savedRole -> {
                        if (request.permissions() == null || request.permissions().isEmpty()) {
                            return Mono.just(savedRole);
                        }
                        return Flux.fromIterable(request.permissions())
                            .flatMap(pe -> {
                                var rp = new RolePermission();
                                // id NOT set — let PostgreSQL generate
                                rp.setRoleId(savedRole.getId());
                                rp.setPermission(pe.permission());
                                rp.setAccessLevel(pe.accessLevelOrDefault());
                                return r2dbcTemplate.insert(rp);
                            })
                            .then(Mono.just(savedRole));
                    })
                    .flatMap(savedRole -> Mono.deferContextual(ctx -> {
                        String tenantId = TenantContext.get(ctx);
                        var event = AuditEvent.create(
                            tenantId != null ? tenantId : "unknown", "Role", savedRole.getId().toString(),
                            savedRole.getName(),
                            "CREATE", actorId, null, null,
                            Map.of("name", savedRole.getName(), "displayName", savedRole.getDisplayName()),
                            new String[]{"name", "displayName", "permissions"},
                            UUID.randomUUID().toString()
                        );
                        // Tenant-wide invalidation — a brand-new role doesn't have assignees yet,
                        // but flushing now is cheap and protects against cache races on rapid assign.
                        Mono<Void> invalidate = tenantId != null
                                ? eventPublisher.publishPermissionsInvalidated(tenantId, null)
                                : Mono.empty();
                        return auditPublisher.publish(event).then(invalidate).thenReturn(savedRole);
                    }));
            });
    }

    @Transactional
    public Mono<UserRole> assignRole(AssignRoleRequest request, String actorId) {
        return userRoleRepository.existsByUserIdAndRoleId(request.userId(), request.roleId())
            .flatMap(exists -> {
                if (exists) return userRoleRepository.findByUserId(request.userId())
                    .filter(ur -> ur.getRoleId().equals(request.roleId()))
                    .next();

                var userRole = new UserRole();
                // id NOT set — let PostgreSQL generate
                userRole.setUserId(request.userId());
                userRole.setRoleId(request.roleId());
                userRole.setAssignedAt(Instant.now());
                userRole.setAssignedBy(UUID.fromString(actorId));

                return r2dbcTemplate.insert(userRole)
                    .flatMap(saved -> roleRepository.findById(request.roleId())
                        .flatMap(role -> Mono.deferContextual(ctx -> {
                            String tenantId = TenantContext.get(ctx);
                            // Targeted invalidation — only this user's cache needs flushing.
                            Mono<Void> invalidate = tenantId != null
                                    ? eventPublisher.publishPermissionsInvalidated(tenantId, request.userId().toString())
                                    : Mono.empty();
                            return eventPublisher.publishRoleAssigned(
                                request.userId().toString(),
                                request.roleId().toString(),
                                role.getName()
                            ).then(invalidate).then(Mono.deferContextual(innerCtx -> {
                                var event = AuditEvent.create(
                                    tenantId != null ? tenantId : "unknown", "UserRole", saved.getId().toString(),
                                    role.getName(),
                                    "CREATE", actorId, null, null,
                                    Map.of("userId", request.userId().toString(), "roleName", role.getName()),
                                    new String[]{"userId", "roleId"},
                                    UUID.randomUUID().toString()
                                );
                                return auditPublisher.publish(event);
                            })).thenReturn(saved);
                        })));
            });
    }

    @Transactional
    public Mono<Void> seedDefaultRoles(String tenantId) {
        log.info("Seeding default roles for tenant: {}", tenantId);

        var defaultRoles = java.util.List.of(
            Map.entry("TENANT_ADMIN",    "Tenant Administrator"),
            Map.entry("OPERATIONS",      "Operations Staff"),
            Map.entry("CLAIMS_OFFICER",  "Claims Officer"),
            Map.entry("FINANCE_OFFICER", "Finance Officer"),
            Map.entry("PROVIDER",        "Service Provider"),
            Map.entry("MEMBER",          "Scheme Member")
        );

        return Flux.fromIterable(defaultRoles)
            .flatMap(entry -> roleRepository.existsByName(entry.getKey())
                .flatMap(exists -> {
                    if (exists) {
                        log.debug("Role {} already exists for tenant {}, skipping", entry.getKey(), tenantId);
                        return Mono.<Role>empty();
                    }
                    var role = new Role();
                    // id NOT set — let PostgreSQL generate via DEFAULT gen_random_uuid()
                    role.setName(entry.getKey());
                    role.setDisplayName(entry.getValue());
                    role.setDescription("Default system role: " + entry.getValue());
                    role.setIsSystem(true);
                    return r2dbcTemplate.insert(role);
                }))
            .then();
    }

    /**
     * Update editable metadata on a role. {@code name} is intentionally
     * immutable to keep the stable identifier consistent across audit logs
     * and any future Keycloak realm-role mirror. {@code is_system} roles
     * (e.g. seeded {@code tenant_admin}) reject updates to prevent the IT
     * admin from breaking the role they need to manage roles.
     */
    @Transactional
    public Mono<Role> update(UUID id, UpdateRoleRequest request, String actorId) {
        return roleRepository.findById(id)
                .switchIfEmpty(Mono.error(new RoleNotFoundException(id)))
                .flatMap(role -> {
                    if (Boolean.TRUE.equals(role.getIsSystem())) {
                        return Mono.error(new IllegalStateException(
                                "Cannot edit system role: " + role.getName()));
                    }
                    role.setDisplayName(request.displayName());
                    role.setDescription(request.description());
                    return roleRepository.save(role)
                            .flatMap(saved -> Mono.deferContextual(ctx -> {
                                String tenantId = TenantContext.get(ctx);
                                Mono<Void> invalidate = tenantId != null
                                        ? eventPublisher.publishPermissionsInvalidated(tenantId, null)
                                        : Mono.empty();
                                return invalidate.thenReturn(saved);
                            }));
                });
    }

    /**
     * Delete a tenant-defined role. The {@code user_roles} foreign key has
     * {@code ON DELETE CASCADE}, so every user previously holding this role
     * loses it atomically. System roles refuse to be deleted.
     */
    @Transactional
    public Mono<Void> delete(UUID id, String actorId) {
        return roleRepository.findById(id)
                .switchIfEmpty(Mono.error(new RoleNotFoundException(id)))
                .flatMap(role -> {
                    if (Boolean.TRUE.equals(role.getIsSystem())) {
                        return Mono.error(new IllegalStateException(
                                "Cannot delete system role: " + role.getName()));
                    }
                    return rolePermissionRepository.deleteByRoleId(id)
                            .then(roleRepository.deleteById(id))
                            .then(Mono.deferContextual(ctx -> {
                                // Tenant-wide flush — every user that held this role has lost it
                                // (FK cascade) and their cached permission set is now stale.
                                String tenantId = TenantContext.get(ctx);
                                return tenantId != null
                                        ? eventPublisher.publishPermissionsInvalidated(tenantId, null)
                                        : Mono.<Void>empty();
                            }));
                });
    }

    /**
     * Atomically replace a role's permission set. Existing rows are deleted
     * and the new ones are inserted in a single transaction so partial
     * failures don't leave the role in a half-applied state. Every key is
     * validated against {@link Permissions#ALL} — unknown keys reject the
     * whole request before any DB writes happen.
     */
    @Transactional
    public Mono<RoleResponse> replacePermissions(UUID id, ReplacePermissionsRequest request, String actorId) {
        for (String key : request.permissions()) {
            if (!Permissions.ALL.contains(key)) {
                return Mono.error(new IllegalArgumentException("Unknown permission key: " + key));
            }
        }
        return roleRepository.findById(id)
                .switchIfEmpty(Mono.error(new RoleNotFoundException(id)))
                .flatMap(role -> rolePermissionRepository.deleteByRoleId(id)
                        .thenMany(Flux.fromIterable(request.permissions()))
                        .flatMap(perm -> {
                            var rp = new RolePermission();
                            rp.setRoleId(id);
                            rp.setPermission(perm);
                            rp.setAccessLevel("full");
                            return r2dbcTemplate.insert(rp);
                        })
                        .then(rolePermissionRepository.findByRoleId(id)
                                .map(rp -> new RoleResponse.PermissionResponse(
                                        rp.getId(), rp.getPermission(), rp.getAccessLevel()))
                                .collectList()
                                .map(perms -> RoleResponse.from(role, perms)))
                        .flatMap(response -> Mono.deferContextual(ctx -> {
                            // Every user that holds this role just had their effective
                            // permissions change — flush the whole tenant cache.
                            String tenantId = TenantContext.get(ctx);
                            Mono<Void> invalidate = tenantId != null
                                    ? eventPublisher.publishPermissionsInvalidated(tenantId, null)
                                    : Mono.empty();
                            return invalidate.thenReturn(response);
                        })));
    }

    /**
     * Resolve the union of permissions across every role assigned to the
     * given user. Powers {@code GET /api/v1/me/permissions} which the
     * Angular {@code PermissionService} calls once on app init to populate
     * the {@code *hasPermission} directive's source set.
     *
     * <p>No caching here — the controller layer adds Caffeine caching keyed
     * by {@code (tenantId, userId)} (see Phase 1.5).
     */
    public Mono<Set<String>> getUserPermissions(UUID userId) {
        return userRoleRepository.findByUserId(userId)
                .flatMap(ur -> rolePermissionRepository.findByRoleId(ur.getRoleId()))
                .map(RolePermission::getPermission)
                .collect(java.util.stream.Collectors.<String>toSet())
                .flatMap(perms -> {
                    if (!perms.isEmpty()) return Mono.just(perms);
                    // Legacy bootstrap — users created before Phase 1.7 don't have
                    // user_roles rows yet. If their staff_users.realm_role names a
                    // DB role in the tenant schema, return its permissions so the
                    // app stays usable while we backfill assignments.
                    return bootstrapPermissionsFromRealmRole(userId);
                });
    }

    /**
     * Resolve permissions via the legacy {@code staff_users.realm_role} string
     * for users with no {@code user_roles} rows yet. The lookup name-matches
     * the realm role against tenant-schema {@code roles.name}; the returned
     * set is the permission catalogue of that role. Empty when no match.
     */
    private Mono<Set<String>> bootstrapPermissionsFromRealmRole(UUID userId) {
        return staffUserRepository.findById(userId)
                .map(staff -> staff.getRealmRole())
                .filter(roleName -> roleName != null && !roleName.isBlank())
                .flatMap(roleName -> roleRepository.findByName(roleName)
                        .flatMapMany(role -> rolePermissionRepository.findByRoleId(role.getId()))
                        .map(RolePermission::getPermission)
                        .collect(java.util.stream.Collectors.<String>toSet()))
                .defaultIfEmpty(Set.of());
    }

    /** Returns the canonical permission catalogue for the role-editor UI. */
    public PermissionCatalogueResponse getCatalogue() {
        List<PermissionCatalogueResponse.Domain> domains = PermissionCatalogue.DOMAINS.stream()
                .map(d -> new PermissionCatalogueResponse.Domain(
                        d.id(),
                        d.label(),
                        d.permissions().stream()
                                .map(p -> new PermissionCatalogueResponse.Permission(
                                        p.key(), p.label(), p.description()))
                                .toList()))
                .toList();
        return new PermissionCatalogueResponse(domains);
    }

    /**
     * Generate a Keycloak-safe role name from a human role name. Lowercase,
     * dashes for whitespace, alphanumeric only — Keycloak realm-role names
     * accept these characters and stay readable in admin UIs.
     */
    private static String slugify(String name) {
        return name.trim().toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
    }

    @Transactional
    public Mono<Void> revokeRole(UUID userId, UUID roleId, String actorId) {
        return userRoleRepository.deleteByUserIdAndRoleId(userId, roleId)
            .then(Mono.deferContextual(ctx -> {
                String tenantId = TenantContext.get(ctx);
                var event = AuditEvent.create(
                    tenantId != null ? tenantId : "unknown", "UserRole",
                    userId.toString() + ":" + roleId.toString(),
                    roleId.toString(),
                    "DELETE", actorId, null,
                    Map.of("userId", userId.toString(), "roleId", roleId.toString()),
                    null,
                    new String[]{"userId", "roleId"},
                    UUID.randomUUID().toString()
                );
                Mono<Void> invalidate = tenantId != null
                        ? eventPublisher.publishPermissionsInvalidated(tenantId, userId.toString())
                        : Mono.empty();
                return auditPublisher.publish(event).then(invalidate);
            }));
    }
}

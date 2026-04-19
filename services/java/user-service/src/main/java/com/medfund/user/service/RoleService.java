package com.medfund.user.service;

import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import com.medfund.user.dto.AssignRoleRequest;
import com.medfund.user.dto.CreateRoleRequest;
import com.medfund.user.dto.RoleResponse;
import com.medfund.user.entity.Role;
import com.medfund.user.entity.RolePermission;
import com.medfund.user.entity.UserRole;
import com.medfund.user.exception.DuplicateRoleException;
import com.medfund.user.exception.RoleNotFoundException;
import com.medfund.user.repository.RolePermissionRepository;
import com.medfund.user.repository.RoleRepository;
import com.medfund.user.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RoleService {

    private final RoleRepository roleRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final UserRoleRepository userRoleRepository;
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

    @Transactional
    public Mono<Role> create(CreateRoleRequest request, String actorId) {
        return roleRepository.existsByName(request.name())
            .flatMap(exists -> {
                if (exists) return Mono.error(new DuplicateRoleException(request.name()));

                var role = new Role();
                // id NOT set — let PostgreSQL generate via DEFAULT gen_random_uuid()
                role.setName(request.name());
                role.setDisplayName(request.displayName());
                role.setDescription(request.description());
                role.setIsSystem(false);

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
                        return auditPublisher.publish(event).thenReturn(savedRole);
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
                            return eventPublisher.publishRoleAssigned(
                                request.userId().toString(),
                                request.roleId().toString(),
                                role.getName()
                            ).then(Mono.deferContextual(innerCtx -> {
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
                return auditPublisher.publish(event);
            }));
    }
}

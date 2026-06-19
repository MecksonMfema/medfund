package com.medfund.user.service;

import com.medfund.shared.audit.AuditPublisher;
import com.medfund.user.dto.AssignRoleRequest;
import com.medfund.user.dto.CreateRoleRequest;
import com.medfund.user.entity.Role;
import com.medfund.user.entity.UserRole;
import com.medfund.user.exception.DuplicateRoleException;
import com.medfund.user.repository.RolePermissionRepository;
import com.medfund.user.repository.RoleRepository;
import com.medfund.user.repository.UserRoleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoleServiceTest {

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private RolePermissionRepository rolePermissionRepository;

    @Mock
    private UserRoleRepository userRoleRepository;

    @Mock
    private AuditPublisher auditPublisher;

    @Mock
    private UserEventPublisher eventPublisher;

    @Mock
    private KeycloakSyncService keycloakSyncService;

    @Mock
    private R2dbcEntityTemplate r2dbcTemplate;

    @InjectMocks
    private RoleService roleService;

    @org.junit.jupiter.api.BeforeEach
    void stubInsert() {
        // RoleService now uses r2dbcTemplate.insert(...) instead of repository.save(...)
        // for fresh inserts. Default to echoing the entity back so tests don't need
        // to wire up per-test stubs unless they care about the persistence call.
        // Type-specific matchers are required: insert is generic so a bare any()
        // matcher leaves the resolved overload unstubbed and the call returns null.
        lenient().when(r2dbcTemplate.insert(any(Role.class))).thenAnswer(inv -> {
            Role r = inv.getArgument(0);
            if (r.getId() == null) r.setId(UUID.randomUUID());
            return Mono.just(r);
        });
        lenient().when(r2dbcTemplate.insert(any(com.medfund.user.entity.RolePermission.class))).thenAnswer(inv -> {
            com.medfund.user.entity.RolePermission rp = inv.getArgument(0);
            if (rp.getId() == null) rp.setId(UUID.randomUUID());
            return Mono.just(rp);
        });
        lenient().when(r2dbcTemplate.insert(any(UserRole.class))).thenAnswer(inv -> {
            UserRole ur = inv.getArgument(0);
            if (ur.getId() == null) ur.setId(UUID.randomUUID());
            return Mono.just(ur);
        });
        lenient().when(eventPublisher.publishPermissionsInvalidated(any(), any())).thenReturn(Mono.empty());
    }

    @Test
    void findAll_returnsRoles() {
        var role1 = createTestRole();
        var role2 = createTestRole();
        role2.setName("admin_role");

        when(roleRepository.findAllOrderByName()).thenReturn(Flux.just(role1, role2));

        StepVerifier.create(roleService.findAll())
            .expectNext(role1)
            .expectNext(role2)
            .verifyComplete();

        verify(roleRepository).findAllOrderByName();
    }

    @Test
    void create_validRequest_createsRoleWithPermissions() {
        var actorId = UUID.randomUUID().toString();
        var request = new CreateRoleRequest("test_role", "Test Role", "A test role", List.of(
            new CreateRoleRequest.PermissionEntry("claims:view", "full")
        ));

        when(roleRepository.existsByName("test_role")).thenReturn(Mono.just(false));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
            roleService.create(request, actorId, "actor@test.example")
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
            .assertNext(role -> {
                assertThat(role.getName()).isEqualTo("test_role");
                assertThat(role.getDisplayName()).isEqualTo("Test Role");
                assertThat(role.getDescription()).isEqualTo("A test role");
                assertThat(role.getIsSystem()).isFalse();
            })
            .verifyComplete();

        verify(r2dbcTemplate).insert(any(Role.class));
        verify(auditPublisher).publish(any());
    }

    @Test
    void create_duplicateName_throwsConflict() {
        var actorId = UUID.randomUUID().toString();
        var request = new CreateRoleRequest("test_role", "Test Role", "A test role", List.of(
            new CreateRoleRequest.PermissionEntry("claims:view", "full")
        ));

        when(roleRepository.existsByName("test_role")).thenReturn(Mono.just(true));

        StepVerifier.create(
            roleService.create(request, actorId, "actor@test.example")
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
            .expectError(DuplicateRoleException.class)
            .verify();
    }

    @Test
    void assignRole_newAssignment_createsUserRole() {
        var actorId = UUID.randomUUID().toString();
        var userId = UUID.randomUUID();
        var roleId = UUID.randomUUID();
        var request = new AssignRoleRequest(userId, roleId);

        var role = createTestRole();
        role.setId(roleId);

        when(userRoleRepository.existsByUserIdAndRoleId(userId, roleId)).thenReturn(Mono.just(false));
        when(roleRepository.findById(roleId)).thenReturn(Mono.just(role));
        when(eventPublisher.publishRoleAssigned(any(), any(), any())).thenReturn(Mono.empty());
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
            roleService.assignRole(request, actorId, "actor@test.example")
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
            .assertNext(userRole -> {
                assertThat(userRole.getUserId()).isEqualTo(userId);
                assertThat(userRole.getRoleId()).isEqualTo(roleId);
                assertThat(userRole.getAssignedBy()).isEqualTo(UUID.fromString(actorId));
            })
            .verifyComplete();

        verify(r2dbcTemplate).insert(any(UserRole.class));
        verify(eventPublisher).publishRoleAssigned(any(), any(), any());
    }

    private Role createTestRole() {
        var role = new Role();
        role.setId(UUID.randomUUID());
        role.setName("test_role");
        role.setDisplayName("Test Role");
        role.setDescription("A test role");
        role.setIsSystem(false);
        role.setCreatedAt(Instant.now());
        role.setUpdatedAt(Instant.now());
        return role;
    }
}

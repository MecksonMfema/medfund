package com.medfund.user.service;

import com.medfund.shared.audit.AuditPublisher;
import com.medfund.user.dto.CreateGroupRequest;
import com.medfund.user.dto.UpdateGroupRequest;
import com.medfund.user.entity.Group;
import com.medfund.user.exception.GroupNotFoundException;
import com.medfund.user.entity.GroupLiaison;
import com.medfund.user.entity.Member;
import com.medfund.user.entity.StaffUser;
import com.medfund.user.repository.GroupLiaisonRepository;
import com.medfund.user.repository.GroupRepository;
import com.medfund.user.repository.MemberRepository;
import com.medfund.user.repository.StaffUserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GroupServiceTest {

    @Mock
    private GroupRepository groupRepository;

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private StaffUserRepository staffUserRepository;

    @Mock
    private GroupLiaisonRepository groupLiaisonRepository;

    @Mock
    private AuditPublisher auditPublisher;

    @Mock
    private R2dbcEntityTemplate r2dbcTemplate;

    @Mock
    private KeycloakSyncService keycloakSyncService;

    @InjectMocks
    private GroupService groupService;

    @BeforeEach
    void stubInsert() {
        // GroupService now inserts via r2dbcTemplate.insert(...) instead of repo.save().
        // In production Postgres stamps an id via DEFAULT gen_random_uuid() and r2dbc
        // populates it on the returned entity; mimic that here so saved.getId() is
        // non-null when the audit log reads it.
        lenient().when(r2dbcTemplate.insert(any(Group.class))).thenAnswer(inv -> {
            Group g = inv.getArgument(0);
            if (g.getId() == null) g.setId(UUID.randomUUID());
            return Mono.just(g);
        });
        // Keycloak sync side effects all log-and-swallow; for tests we just
        // assert they were called (or not) — stub them as no-ops.
        lenient().when(keycloakSyncService.ensureRealmRole(anyString(), anyString(), anyString()))
            .thenReturn(Mono.empty());
        lenient().when(keycloakSyncService.assignRealmRoles(anyString(), anyString(), any()))
            .thenReturn(Mono.empty());
    }

    @Test
    void findAll_returnsGroups() {
        var group1 = createTestGroup();
        var group2 = createTestGroup();
        group2.setName("Beta Corp");

        when(groupRepository.findAllOrderByCreatedAtDesc()).thenReturn(Flux.just(group1, group2));

        StepVerifier.create(groupService.findAll())
            .expectNext(group1)
            .expectNext(group2)
            .verifyComplete();

        verify(groupRepository).findAllOrderByCreatedAtDesc();
    }

    @Test
    void findById_existing_returnsGroup() {
        var group = createTestGroup();
        var id = group.getId();

        when(groupRepository.findById(id)).thenReturn(Mono.just(group));

        StepVerifier.create(groupService.findById(id))
            .assertNext(result -> {
                assertThat(result.getId()).isEqualTo(id);
                assertThat(result.getName()).isEqualTo("Acme Corp");
            })
            .verifyComplete();
    }

    @Test
    void findById_nonExisting_throwsNotFound() {
        var id = UUID.randomUUID();

        when(groupRepository.findById(id)).thenReturn(Mono.empty());

        StepVerifier.create(groupService.findById(id))
            .expectError(GroupNotFoundException.class)
            .verify();
    }

    @Test
    void create_validRequest_createsGroup() {
        var actorId = UUID.randomUUID().toString();
        var request = new CreateGroupRequest(
            "Acme Corp", "REG-001", "Jane Smith", "jane@acme.com", null, null,
            null, null
        );

        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
            groupService.create(request, actorId)
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
            .assertNext(group -> {
                assertThat(group.getStatus()).isEqualTo("active");
                assertThat(group.getName()).isEqualTo("Acme Corp");
                assertThat(group.getRegistrationNumber()).isEqualTo("REG-001");
                assertThat(group.getContactPerson()).isEqualTo("Jane Smith");
            })
            .verifyComplete();

        verify(r2dbcTemplate).insert(any(Group.class));
        verify(auditPublisher).publish(any());
    }

    @Test
    void update_existingGroup_updatesFields() {
        var group = createTestGroup();
        var id = group.getId();
        var actorId = UUID.randomUUID().toString();
        var request = new UpdateGroupRequest(
            "Updated Corp", null, "John Smith", "john@acme.com", null, null,
            null, null
        );

        when(groupRepository.findById(id)).thenReturn(Mono.just(group));
        when(groupRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
            groupService.update(id, request, actorId)
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
            .assertNext(result -> {
                assertThat(result.getName()).isEqualTo("Updated Corp");
                assertThat(result.getContactPerson()).isEqualTo("John Smith");
                assertThat(result.getContactEmail()).isEqualTo("john@acme.com");
                assertThat(result.getRegistrationNumber()).isEqualTo("REG-001");
            })
            .verifyComplete();
    }

    @Test
    void suspend_existingGroup_setsStatusSuspended() {
        var group = createTestGroup();
        var id = group.getId();
        var actorId = UUID.randomUUID().toString();

        when(groupRepository.findById(id)).thenReturn(Mono.just(group));
        when(groupRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
            groupService.suspend(id, actorId)
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
            .assertNext(result -> assertThat(result.getStatus()).isEqualTo("suspended"))
            .verifyComplete();

        verify(auditPublisher).publish(any());
    }

    // ── Liaison handling ────────────────────────────────────────────────

    @Test
    void create_withMemberLiaison_validatesAndPersists() {
        var actorId = UUID.randomUUID().toString();
        var liaisonId = UUID.randomUUID();
        var request = new CreateGroupRequest(
            "Acme", null, null, null, null, null,
            "MEMBER", liaisonId
        );
        when(memberRepository.existsById(liaisonId)).thenReturn(Mono.just(true));
        var memberStub = new Member();
        memberStub.setId(liaisonId);
        memberStub.setKeycloakUserId("kc-mem-1");
        when(memberRepository.findById(liaisonId)).thenReturn(Mono.just(memberStub));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
            groupService.create(request, actorId)
                .contextWrite(ctx -> ctx.put("TENANT_ID", "t1"))
        )
            .assertNext(g -> {
                assertThat(g.getLiaisonKind()).isEqualTo("MEMBER");
                assertThat(g.getLiaisonUserId()).isEqualTo(liaisonId);
            })
            .verifyComplete();

        // The group_liaison realm role is ensured on the tenant realm and
        // assigned to the member's Keycloak account.
        verify(keycloakSyncService).ensureRealmRole(eq("tenant-t1"), eq("group_liaison"), anyString());
        verify(keycloakSyncService).assignRealmRoles(eq("tenant-t1"), eq("kc-mem-1"), any());
    }

    @Test
    void create_withStaffLiaison_validatesAndPersists() {
        var actorId = UUID.randomUUID().toString();
        var liaisonId = UUID.randomUUID();
        var request = new CreateGroupRequest(
            "Acme", null, null, null, null, null,
            "STAFF", liaisonId
        );
        when(staffUserRepository.existsById(liaisonId)).thenReturn(Mono.just(true));
        var staffStub = new StaffUser();
        staffStub.setId(liaisonId);
        staffStub.setKeycloakUserId("kc-staff-1");
        when(staffUserRepository.findById(liaisonId)).thenReturn(Mono.just(staffStub));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
            groupService.create(request, actorId)
                .contextWrite(ctx -> ctx.put("TENANT_ID", "t1"))
        )
            .assertNext(g -> {
                assertThat(g.getLiaisonKind()).isEqualTo("STAFF");
                assertThat(g.getLiaisonUserId()).isEqualTo(liaisonId);
            })
            .verifyComplete();

        // Staff users live in the platform realm.
        verify(keycloakSyncService).ensureRealmRole(eq("medfund-platform"), eq("group_liaison"), anyString());
        verify(keycloakSyncService).assignRealmRoles(eq("medfund-platform"), eq("kc-staff-1"), any());
    }

    @Test
    void create_withPureLiaison_validatesAndAssignsTenantRealmRole() {
        var actorId = UUID.randomUUID().toString();
        var liaisonId = UUID.randomUUID();
        var request = new CreateGroupRequest(
            "Acme", null, null, null, null, null,
            "LIAISON", liaisonId
        );
        when(groupLiaisonRepository.existsById(liaisonId)).thenReturn(Mono.just(true));
        var liaisonStub = new GroupLiaison();
        liaisonStub.setId(liaisonId);
        liaisonStub.setKeycloakUserId("kc-liaison-1");
        when(groupLiaisonRepository.findById(liaisonId)).thenReturn(Mono.just(liaisonStub));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
            groupService.create(request, actorId)
                .contextWrite(ctx -> ctx.put("TENANT_ID", "t1"))
        )
            .assertNext(g -> {
                assertThat(g.getLiaisonKind()).isEqualTo("LIAISON");
                assertThat(g.getLiaisonUserId()).isEqualTo(liaisonId);
            })
            .verifyComplete();

        verify(keycloakSyncService).ensureRealmRole(eq("tenant-t1"), eq("group_liaison"), anyString());
        verify(keycloakSyncService).assignRealmRoles(eq("tenant-t1"), eq("kc-liaison-1"), any());
    }

    @Test
    void create_pureLiaisonNotFound_returns422() {
        var actorId = UUID.randomUUID().toString();
        var liaisonId = UUID.randomUUID();
        var request = new CreateGroupRequest(
            "Acme", null, null, null, null, null,
            "LIAISON", liaisonId
        );
        when(groupLiaisonRepository.existsById(liaisonId)).thenReturn(Mono.just(false));

        StepVerifier.create(
            groupService.create(request, actorId)
                .contextWrite(ctx -> ctx.put("TENANT_ID", "t1"))
        )
            .expectErrorSatisfies(err -> {
                assertThat(err).isInstanceOf(ResponseStatusException.class);
                assertThat(((ResponseStatusException) err).getStatusCode().value()).isEqualTo(422);
                assertThat(err.getMessage()).contains("No group liaison");
            })
            .verify();

        verify(keycloakSyncService, never()).assignRealmRoles(anyString(), anyString(), any());
    }

    @Test
    void create_kindWithoutId_returns422() {
        var actorId = UUID.randomUUID().toString();
        var request = new CreateGroupRequest(
            "Acme", null, null, null, null, null,
            "MEMBER", null
        );

        StepVerifier.create(
            groupService.create(request, actorId)
                .contextWrite(ctx -> ctx.put("TENANT_ID", "t1"))
        )
            .expectErrorSatisfies(err -> {
                assertThat(err).isInstanceOf(ResponseStatusException.class);
                assertThat(((ResponseStatusException) err).getStatusCode().value()).isEqualTo(422);
                assertThat(err.getMessage()).contains("supplied together");
            })
            .verify();
    }

    @Test
    void create_memberLiaisonNotFound_returns422() {
        var actorId = UUID.randomUUID().toString();
        var liaisonId = UUID.randomUUID();
        var request = new CreateGroupRequest(
            "Acme", null, null, null, null, null,
            "MEMBER", liaisonId
        );
        when(memberRepository.existsById(liaisonId)).thenReturn(Mono.just(false));

        StepVerifier.create(
            groupService.create(request, actorId)
                .contextWrite(ctx -> ctx.put("TENANT_ID", "t1"))
        )
            .expectErrorSatisfies(err -> {
                assertThat(err).isInstanceOf(ResponseStatusException.class);
                assertThat(((ResponseStatusException) err).getStatusCode().value()).isEqualTo(422);
                assertThat(err.getMessage()).contains("No member");
            })
            .verify();
    }

    @Test
    void update_clearLiaison_setsBothFieldsNull() {
        var group = createTestGroup();
        group.setLiaisonKind("MEMBER");
        group.setLiaisonUserId(UUID.randomUUID());
        var actorId = UUID.randomUUID().toString();
        var request = new UpdateGroupRequest(
            null, null, null, null, null, null,
            "CLEAR", null
        );

        when(groupRepository.findById(group.getId())).thenReturn(Mono.just(group));
        when(groupRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
            groupService.update(group.getId(), request, actorId)
                .contextWrite(ctx -> ctx.put("TENANT_ID", "t1"))
        )
            .assertNext(g -> {
                assertThat(g.getLiaisonKind()).isNull();
                assertThat(g.getLiaisonUserId()).isNull();
            })
            .verifyComplete();
    }

    @Test
    void update_switchLiaisonKind_validatesNewTarget() {
        var group = createTestGroup();
        group.setLiaisonKind("MEMBER");
        group.setLiaisonUserId(UUID.randomUUID());
        var actorId = UUID.randomUUID().toString();
        var newStaffId = UUID.randomUUID();
        var request = new UpdateGroupRequest(
            null, null, null, null, null, null,
            "STAFF", newStaffId
        );

        when(groupRepository.findById(group.getId())).thenReturn(Mono.just(group));
        when(staffUserRepository.existsById(newStaffId)).thenReturn(Mono.just(true));
        var newStaff = new StaffUser();
        newStaff.setId(newStaffId);
        newStaff.setKeycloakUserId("kc-new-staff");
        when(staffUserRepository.findById(newStaffId)).thenReturn(Mono.just(newStaff));
        when(groupRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
            groupService.update(group.getId(), request, actorId)
                .contextWrite(ctx -> ctx.put("TENANT_ID", "t1"))
        )
            .assertNext(g -> {
                assertThat(g.getLiaisonKind()).isEqualTo("STAFF");
                assertThat(g.getLiaisonUserId()).isEqualTo(newStaffId);
            })
            .verifyComplete();
    }

    private Group createTestGroup() {
        var group = new Group();
        group.setId(UUID.randomUUID());
        group.setName("Acme Corp");
        group.setRegistrationNumber("REG-001");
        group.setContactPerson("Jane Smith");
        group.setContactEmail("jane@acme.com");
        group.setStatus("active");
        group.setCreatedAt(Instant.now());
        group.setUpdatedAt(Instant.now());
        group.setCreatedBy(UUID.randomUUID());
        group.setUpdatedBy(UUID.randomUUID());
        return group;
    }
}

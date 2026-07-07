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

    @Mock
    private MemberService memberService;

    @Mock
    private UserEventPublisher eventPublisher;

    @Mock
    private GroupNumberService groupNumberService;

    @InjectMocks
    private GroupService groupService;

    @BeforeEach
    void stubInsert() {
        // Group creates always ask the number-service for a fresh
        // registration_number now. Stub a deterministic value so the
        // "create + audit publishes …" tests keep working without every
        // test having to arrange the same mock.
        lenient().when(groupNumberService.nextRegistrationNumber())
                .thenReturn(Mono.just("GRP-000001"));
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
        // Every immediate-flip path publishes GROUP_STATUS_CHANGED so the
        // notification-service lifecycle consumer can email the liaison.
        // Stub as no-op here — cascade + scheduled tests use verify(...)
        // to assert exact args.
        lenient().when(eventPublisher.publishGroupLifecycle(any(), any(), any(), any()))
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
        var liaisonId = UUID.randomUUID();
        // The request still carries a registrationNumber field for
        // backwards compatibility with old clients, but GroupService
        // deliberately ignores it — the server-issued value from
        // GroupNumberService wins. Assert both behaviours below.
        var request = new CreateGroupRequest(
            "Acme Corp", "IGNORED-BY-SERVER", null, "billing@acme.test", "MEMBER", liaisonId
        );

        when(memberRepository.existsById(liaisonId)).thenReturn(Mono.just(true));
        var memberStub = new Member();
        memberStub.setId(liaisonId);
        memberStub.setKeycloakUserId("kc-mem-x");
        when(memberRepository.findById(liaisonId)).thenReturn(Mono.just(memberStub));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
            groupService.create(request, actorId, "actor@test.example")
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
            .assertNext(group -> {
                assertThat(group.getStatus()).isEqualTo("active");
                assertThat(group.getName()).isEqualTo("Acme Corp");
                // Server-issued number wins; the request field is
                // discarded. GroupNumberService is stubbed to return
                // GRP-000001 in the @BeforeEach setup.
                assertThat(group.getRegistrationNumber()).isEqualTo("GRP-000001");
                // Email is the fallback recipient when no liaison is assigned;
                // it must survive the create path unchanged.
                assertThat(group.getEmail()).isEqualTo("billing@acme.test");
            })
            .verifyComplete();

        verify(r2dbcTemplate).insert(any(Group.class));
        verify(auditPublisher).publish(any());
        // Explicit proof the generator was consulted — a regression that
        // wired the request value back in would still get the stubbed
        // value on this test, so pin the interaction directly.
        verify(groupNumberService).nextRegistrationNumber();
    }

    @Test
    void update_existingGroup_updatesFields() {
        var group = createTestGroup();
        var id = group.getId();
        var actorId = UUID.randomUUID().toString();
        var request = new UpdateGroupRequest(
            "Updated Corp", null, null, null, null, null
        );

        when(groupRepository.findById(id)).thenReturn(Mono.just(group));
        when(groupRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
            groupService.update(id, request, actorId, "actor@test.example")
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
            .assertNext(result -> {
                assertThat(result.getName()).isEqualTo("Updated Corp");
                assertThat(result.getRegistrationNumber()).isEqualTo("REG-001");
            })
            .verifyComplete();
    }

    /**
     * Locks the "registration_number is server-issued and immutable"
     * contract on update. A regression that re-added
     * {@code existing.setRegistrationNumber(request.registrationNumber())}
     * would let an operator rewrite the number by sending a bogus
     * value on PATCH — silently defeating the whole point of
     * auto-generation (uniqueness under a tenant-defined shape).
     */
    @Test
    void update_ignoresRegistrationNumberInRequest() {
        var group = createTestGroup();   // starts with "REG-001" (from createTestGroup)
        var id = group.getId();
        var actorId = UUID.randomUUID().toString();
        // Bogus registration number in the request. If the update
        // path honoured it, the persisted group would end up as
        // "HACKED-999" — pin the ignore behaviour explicitly.
        var request = new UpdateGroupRequest(
            null, "HACKED-999", null, null, null, null
        );

        when(groupRepository.findById(id)).thenReturn(Mono.just(group));
        when(groupRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
            groupService.update(id, request, actorId, "actor@test.example")
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
            .assertNext(result ->
                assertThat(result.getRegistrationNumber())
                    .as("update must not overwrite the original registration number")
                    .isEqualTo("REG-001"))
            .verifyComplete();
    }

    /**
     * Guard against the "null email means no change" rule regressing to
     * "null email wipes the field". The DTO's Optional-null convention has
     * bitten several other fields — pin it here for email too.
     */
    @Test
    void update_nullEmail_leavesExistingUntouched() {
        var group = createTestGroup();
        group.setEmail("keep@acme.test");
        var id = group.getId();
        var actorId = UUID.randomUUID().toString();
        var request = new UpdateGroupRequest(
            "Renamed", null, null, null, null, null
        );

        when(groupRepository.findById(id)).thenReturn(Mono.just(group));
        when(groupRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
            groupService.update(id, request, actorId, "actor@test.example")
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
            .assertNext(saved -> assertThat(saved.getEmail()).isEqualTo("keep@acme.test"))
            .verifyComplete();
    }

    @Test
    void update_providedEmail_replacesExisting() {
        var group = createTestGroup();
        group.setEmail("old@acme.test");
        var id = group.getId();
        var actorId = UUID.randomUUID().toString();
        var request = new UpdateGroupRequest(
            null, null, null, "new@acme.test", null, null
        );

        when(groupRepository.findById(id)).thenReturn(Mono.just(group));
        when(groupRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
            groupService.update(id, request, actorId, "actor@test.example")
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
            .assertNext(saved -> assertThat(saved.getEmail()).isEqualTo("new@acme.test"))
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
            groupService.suspend(id, actorId, "actor@test.example")
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
            "Acme", null, null, "contact@acme.test",
            "MEMBER", liaisonId
        );
        when(memberRepository.existsById(liaisonId)).thenReturn(Mono.just(true));
        var memberStub = new Member();
        memberStub.setId(liaisonId);
        memberStub.setKeycloakUserId("kc-mem-1");
        when(memberRepository.findById(liaisonId)).thenReturn(Mono.just(memberStub));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
            groupService.create(request, actorId, "actor@test.example")
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
            "Acme", null, null, "contact@acme.test",
            "STAFF", liaisonId
        );
        when(staffUserRepository.existsById(liaisonId)).thenReturn(Mono.just(true));
        var staffStub = new StaffUser();
        staffStub.setId(liaisonId);
        staffStub.setKeycloakUserId("kc-staff-1");
        when(staffUserRepository.findById(liaisonId)).thenReturn(Mono.just(staffStub));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
            groupService.create(request, actorId, "actor@test.example")
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
            "Acme", null, null, "contact@acme.test",
            "LIAISON", liaisonId
        );
        when(groupLiaisonRepository.existsById(liaisonId)).thenReturn(Mono.just(true));
        var liaisonStub = new GroupLiaison();
        liaisonStub.setId(liaisonId);
        liaisonStub.setKeycloakUserId("kc-liaison-1");
        when(groupLiaisonRepository.findById(liaisonId)).thenReturn(Mono.just(liaisonStub));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
            groupService.create(request, actorId, "actor@test.example")
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
            "Acme", null, null, "contact@acme.test",
            "LIAISON", liaisonId
        );
        when(groupLiaisonRepository.existsById(liaisonId)).thenReturn(Mono.just(false));

        StepVerifier.create(
            groupService.create(request, actorId, "actor@test.example")
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

    /**
     * The single "you need a way to email this group" rule: neither a
     * liaison nor a contact email → 422. Both being set is fine; either
     * on its own is fine.
     */
    @Test
    void create_withoutLiaisonAndWithoutEmail_returns422() {
        var actorId = UUID.randomUUID().toString();
        var request = new CreateGroupRequest(
            "Acme", null, null, null,
            null, null
        );

        StepVerifier.create(
            groupService.create(request, actorId, "actor@test.example")
                .contextWrite(ctx -> ctx.put("TENANT_ID", "t1"))
        )
            .expectErrorSatisfies(err -> {
                assertThat(err).isInstanceOf(ResponseStatusException.class);
                assertThat(((ResponseStatusException) err).getStatusCode().value()).isEqualTo(422);
                assertThat(err.getMessage())
                    .contains("either a liaison")
                    .contains("contact email");
            })
            .verify();
    }

    /** Email-only is now a valid create shape — no liaison required. */
    @Test
    void create_emailOnly_succeeds() {
        var actorId = UUID.randomUUID().toString();
        var request = new CreateGroupRequest(
            "Acme", null, null, "billing@acme.test",
            null, null
        );
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
            groupService.create(request, actorId, "actor@test.example")
                .contextWrite(ctx -> ctx.put("TENANT_ID", "t1"))
        )
            .assertNext(g -> {
                assertThat(g.getEmail()).isEqualTo("billing@acme.test");
                assertThat(g.getLiaisonKind()).isNull();
                assertThat(g.getLiaisonUserId()).isNull();
            })
            .verifyComplete();

        // No liaison → the Keycloak role-grant path must not fire.
        verify(keycloakSyncService, never()).assignRealmRoles(anyString(), anyString(), any());
    }

    /** Liaison-only (no email) is now valid — the resolver still has a route. */
    @Test
    void create_liaisonOnlyNoEmail_succeeds() {
        var actorId = UUID.randomUUID().toString();
        var liaisonId = UUID.randomUUID();
        var request = new CreateGroupRequest(
            "Acme", null, null, null,
            "MEMBER", liaisonId
        );
        when(memberRepository.existsById(liaisonId)).thenReturn(Mono.just(true));
        var memberStub = new Member();
        memberStub.setId(liaisonId);
        memberStub.setKeycloakUserId("kc-mem-x");
        when(memberRepository.findById(liaisonId)).thenReturn(Mono.just(memberStub));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
            groupService.create(request, actorId, "actor@test.example")
                .contextWrite(ctx -> ctx.put("TENANT_ID", "t1"))
        )
            .assertNext(g -> {
                assertThat(g.getLiaisonKind()).isEqualTo("MEMBER");
                assertThat(g.getEmail()).isNull();
            })
            .verifyComplete();
    }

    /**
     * The update-time twin of the create rule: an UPDATE cannot leave the
     * group with neither liaison nor email, or subsequent statement
     * dispatches have nowhere to route.
     */
    @Test
    void update_wouldLeaveBothEmpty_returns422() {
        var group = createTestGroup();
        group.setEmail("current@acme.test");
        // No liaison assigned — the group is currently reachable via email only.
        var id = group.getId();
        var actorId = UUID.randomUUID().toString();
        // Blank email clears; no liaison touch → post-update both are empty.
        var request = new UpdateGroupRequest(
            null, null, null, "", null, null
        );

        when(groupRepository.findById(id)).thenReturn(Mono.just(group));

        StepVerifier.create(
            groupService.update(id, request, actorId, "actor@test.example")
                .contextWrite(ctx -> ctx.put("TENANT_ID", "t1"))
        )
            .expectErrorSatisfies(err -> {
                assertThat(err).isInstanceOf(ResponseStatusException.class);
                assertThat(((ResponseStatusException) err).getStatusCode().value()).isEqualTo(422);
                assertThat(err.getMessage()).contains("leave both empty");
            })
            .verify();

        verify(groupRepository, never()).save(any());
    }

    @Test
    void create_kindWithoutId_returns422() {
        var actorId = UUID.randomUUID().toString();
        var request = new CreateGroupRequest(
            "Acme", null, null, "contact@acme.test",
            "MEMBER", null
        );

        StepVerifier.create(
            groupService.create(request, actorId, "actor@test.example")
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
            "Acme", null, null, "contact@acme.test",
            "MEMBER", liaisonId
        );
        when(memberRepository.existsById(liaisonId)).thenReturn(Mono.just(false));

        StepVerifier.create(
            groupService.create(request, actorId, "actor@test.example")
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
            null, null, null, null,
            "CLEAR", null
        );

        when(groupRepository.findById(group.getId())).thenReturn(Mono.just(group));
        when(groupRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
            groupService.update(group.getId(), request, actorId, "actor@test.example")
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
            null, null, null, null,
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
            groupService.update(group.getId(), request, actorId, "actor@test.example")
                .contextWrite(ctx -> ctx.put("TENANT_ID", "t1"))
        )
            .assertNext(g -> {
                assertThat(g.getLiaisonKind()).isEqualTo("STAFF");
                assertThat(g.getLiaisonUserId()).isEqualTo(newStaffId);
            })
            .verifyComplete();
    }

    // ------------------------------------------------------------------
    // Future-dated + cascade tests (V042/V043)
    // ------------------------------------------------------------------

    @Test
    void deactivate_futureDate_persistsScheduledTrio_noPublishNoCascade() {
        var group = createTestGroup();
        var id = group.getId();
        // Deactivate is a termination — feedback_effective_date_snap snaps
        // to end-of-month. Anchor 60 days out so the snapped date lands in
        // the future regardless of when the test runs.
        var effective = java.time.LocalDate.now().plusDays(60);
        var expectedSnapped = effective.withDayOfMonth(effective.lengthOfMonth());

        when(groupRepository.findById(id)).thenReturn(Mono.just(group));
        when(groupRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
            groupService.deactivate(id, effective, "PLANNED",
                    UUID.randomUUID().toString(), "actor@test")
                .contextWrite(ctx -> ctx.put("TENANT_ID", "tnt"))
        )
            .assertNext(g -> {
                assertThat(g.getStatus()).isEqualTo("active"); // unchanged
                assertThat(g.getScheduledStatus()).isEqualTo("deactivated");
                assertThat(g.getScheduledStatusEffectiveFrom()).isEqualTo(expectedSnapped);
                assertThat(g.getScheduledStatusReason()).isEqualTo("PLANNED");
            })
            .verifyComplete();

        // Scheduled flips are audit-only — no lifecycle event, no cascade.
        verify(eventPublisher, never()).publishGroupLifecycle(any(), any(), any(), any());
        verify(memberService, never()).deactivate(any(), any(), any(), any(), any());
        verify(memberService, never()).terminate(any(), any(), any(), any(), any());
    }

    @Test
    void deactivate_immediate_cascadesActiveAndSuspendedMembers_skipsTerminatedAndDeactivated() {
        var group = createTestGroup();
        var id = group.getId();

        when(groupRepository.findById(id)).thenReturn(Mono.just(group));
        when(groupRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        Member active = memberInGroup(id, "active");
        Member suspended = memberInGroup(id, "suspended");
        Member terminated = memberInGroup(id, "terminated");
        Member alreadyDeactivated = memberInGroup(id, "deactivated");
        when(memberRepository.findByGroupId(id))
                .thenReturn(Flux.just(active, suspended, terminated, alreadyDeactivated));
        when(memberService.deactivate(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> Mono.just(active));

        StepVerifier.create(
            groupService.deactivate(id, null, "ARREARS_ESCALATION",
                    UUID.randomUUID().toString(), "actor@test")
                .contextWrite(ctx -> ctx.put("TENANT_ID", "tnt"))
        )
            .assertNext(g -> assertThat(g.getStatus()).isEqualTo("deactivated"))
            .verifyComplete();

        // Guards:
        //  - active + suspended → cascade fires
        //  - terminated + already-deactivated → skipped per the plan
        //  - publish carries tenantId + reason in the right slots
        verify(memberService).deactivate(eq(active.getId()), org.mockito.ArgumentMatchers.isNull(),
                eq("ARREARS_ESCALATION"), any(), any());
        verify(memberService).deactivate(eq(suspended.getId()), org.mockito.ArgumentMatchers.isNull(),
                eq("ARREARS_ESCALATION"), any(), any());
        verify(memberService, never())
                .deactivate(eq(terminated.getId()), any(), any(), any(), any());
        verify(memberService, never())
                .deactivate(eq(alreadyDeactivated.getId()), any(), any(), any(), any());
        verify(eventPublisher).publishGroupLifecycle(eq("tnt"), eq(id.toString()),
                eq("deactivated"), eq("ARREARS_ESCALATION"));
    }

    @Test
    void suspend_immediate_doesNotCascadeToMembers() {
        // suspend is not a cascading action — only deactivate and terminate cascade
        // per the plan. Members keep their individual status.
        var group = createTestGroup();
        var id = group.getId();

        when(groupRepository.findById(id)).thenReturn(Mono.just(group));
        when(groupRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
            groupService.suspend(id, null, "ARREARS_ESCALATION",
                    UUID.randomUUID().toString(), "actor@test")
                .contextWrite(ctx -> ctx.put("TENANT_ID", "tnt"))
        )
            .assertNext(g -> {
                assertThat(g.getStatus()).isEqualTo("suspended");
                assertThat(g.getSuspendReason()).isEqualTo("ARREARS_ESCALATION");
            })
            .verifyComplete();

        verify(memberService, never()).suspend(any(), any(), any(), any(), any());
        verify(memberService, never()).deactivate(any(), any(), any(), any(), any());
    }

    @Test
    void activate_afterSuspend_clearsSuspendReason() {
        var group = createTestGroup();
        group.setStatus("suspended");
        group.setSuspendReason("ARREARS_ESCALATION");
        var id = group.getId();

        when(groupRepository.findById(id)).thenReturn(Mono.just(group));
        when(groupRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
            groupService.activate(id, null, "ARREARS_CLEARED",
                    UUID.randomUUID().toString(), "actor@test")
                .contextWrite(ctx -> ctx.put("TENANT_ID", "tnt"))
        )
            .assertNext(g -> {
                assertThat(g.getStatus()).isEqualTo("active");
                assertThat(g.getSuspendReason()).isNull();
            })
            .verifyComplete();
    }

    private static Member memberInGroup(UUID groupId, String status) {
        Member m = new Member();
        m.setId(UUID.randomUUID());
        m.setGroupId(groupId);
        m.setStatus(status);
        return m;
    }

    private Group createTestGroup() {
        var group = new Group();
        group.setId(UUID.randomUUID());
        group.setName("Acme Corp");
        group.setRegistrationNumber("REG-001");
        // Baseline groups carry a fallback email so update-time tests aren't
        // tripped by the "leave both empty" 422 — the tests that specifically
        // exercise the emptiness rule override this to null.
        group.setEmail("existing@acme.test");
        group.setStatus("active");
        group.setCreatedAt(Instant.now());
        group.setUpdatedAt(Instant.now());
        group.setCreatedBy(UUID.randomUUID());
        group.setUpdatedBy(UUID.randomUUID());
        return group;
    }
}

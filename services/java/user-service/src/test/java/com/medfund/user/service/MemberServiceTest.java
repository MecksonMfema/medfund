package com.medfund.user.service;

import com.medfund.shared.audit.AuditPublisher;
import com.medfund.user.dto.CreateMemberRequest;
import com.medfund.user.entity.Member;
import com.medfund.user.exception.MemberNotFoundException;
import com.medfund.user.repository.MemberRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;


@ExtendWith(MockitoExtension.class)
class MemberServiceTest {

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private AuditPublisher auditPublisher;

    @Mock
    private UserEventPublisher eventPublisher;

    @Mock
    private KeycloakSyncService keycloakSyncService;

    @Mock
    private R2dbcEntityTemplate r2dbcTemplate;

    @Mock
    private MemberLifecycleService lifecycleService;

    @InjectMocks
    private MemberService memberService;

    @BeforeEach
    void stubInsert() {
        // Fresh members are inserted via r2dbcTemplate; postgres assigns the id
        // through DEFAULT gen_random_uuid() — mimic that here so audit logging
        // (which reads saved.getId()) doesn't NPE.
        lenient().when(r2dbcTemplate.insert(any(Member.class))).thenAnswer(inv -> {
            Member m = inv.getArgument(0);
            if (m.getId() == null) m.setId(UUID.randomUUID());
            return Mono.just(m);
        });
        // The post-enrollment lifecycle hook is exercised by its own tests;
        // here it just needs to be a no-op (empty Mono completes without firing
        // the doOnNext branch in MemberService.enroll).
        lenient().when(lifecycleService.evaluateOnEnrollment(any(Member.class)))
                .thenReturn(Mono.empty());
    }

    @Test
    void findAll_returnsAllMembers() {
        var member1 = createTestMember();
        var member2 = createTestMember();
        member2.setMemberNumber("MBR-789012");

        when(memberRepository.findAllOrderByCreatedAtDesc()).thenReturn(Flux.just(member1, member2));

        StepVerifier.create(memberService.findAll())
            .expectNext(member1)
            .expectNext(member2)
            .verifyComplete();

        verify(memberRepository).findAllOrderByCreatedAtDesc();
    }

    @Test
    void findById_existing_returnsMember() {
        var member = createTestMember();
        var id = member.getId();

        when(memberRepository.findById(id)).thenReturn(Mono.just(member));

        StepVerifier.create(memberService.findById(id))
            .assertNext(result -> {
                assertThat(result.getId()).isEqualTo(id);
                assertThat(result.getMemberNumber()).isEqualTo("MBR-123456");
            })
            .verifyComplete();
    }

    @Test
    void findById_nonExisting_throwsNotFound() {
        var id = UUID.randomUUID();

        when(memberRepository.findById(id)).thenReturn(Mono.empty());

        StepVerifier.create(memberService.findById(id))
            .expectError(MemberNotFoundException.class)
            .verify();
    }

    @Test
    void findByMemberNumber_existing_returnsMember() {
        var member = createTestMember();

        when(memberRepository.findByMemberNumber("MBR-123456")).thenReturn(Mono.just(member));

        StepVerifier.create(memberService.findByMemberNumber("MBR-123456"))
            .assertNext(result -> assertThat(result.getMemberNumber()).isEqualTo("MBR-123456"))
            .verifyComplete();
    }

    @Test
    void enroll_validRequest_createsMember() {
        var actorId = UUID.randomUUID().toString();
        var request = new CreateMemberRequest(
            "John", "Doe", LocalDate.of(1990, 1, 15), "male",
            null, "john@example.com", null, null, null, null, null
        );

        when(memberRepository.existsByMemberNumber(any())).thenReturn(Mono.just(false));
        when(memberRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        when(eventPublisher.publishMemberEnrolled(any(), any(), any())).thenReturn(Mono.empty());
        when(keycloakSyncService.createUser(any(), any(), any(), any(), any())).thenReturn(Mono.just("kc-user-id"));

        StepVerifier.create(
            memberService.enroll(request, actorId, "actor@test.example")
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
            .assertNext(member -> {
                assertThat(member.getStatus()).isEqualTo("enrolled");
                assertThat(member.getMemberNumber()).startsWith("MBR-");
                assertThat(member.getEnrollmentDate()).isNotNull();
                assertThat(member.getFirstName()).isEqualTo("John");
                assertThat(member.getLastName()).isEqualTo("Doe");
                assertThat(member.getEmail()).isEqualTo("john@example.com");
            })
            .verifyComplete();

        verify(memberRepository, atLeast(1)).save(any());
        verify(auditPublisher).publish(any());
        verify(eventPublisher).publishMemberEnrolled(any(), any(), any());
    }

    @Test
    void enroll_nonUuidActorId_persistsNullActor() {
        // Legacy JWT subs (e.g. usernames) are not parseable as UUIDs. The
        // safeParseUuid helper must leave createdBy/updatedBy as null instead
        // of throwing — otherwise a malformed sub claim blocks enrolment.
        var legacyActorId = "legacy-username";
        var request = new CreateMemberRequest(
            "John", "Doe", LocalDate.of(1990, 1, 15), "male",
            "63-1234567", "john@example.com", null, null, null, UUID.randomUUID(), null
        );

        when(memberRepository.existsByMemberNumber(any())).thenReturn(Mono.just(false));
        when(memberRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        when(eventPublisher.publishMemberEnrolled(any(), any(), any())).thenReturn(Mono.empty());
        when(keycloakSyncService.createUser(any(), any(), any(), any(), any())).thenReturn(Mono.just("kc-user-id"));

        StepVerifier.create(
            memberService.enroll(request, legacyActorId, "actor@test.example")
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
            .assertNext(member -> {
                assertThat(member.getCreatedBy()).isNull();
                assertThat(member.getUpdatedBy()).isNull();
                assertThat(member.getStatus()).isEqualTo("enrolled");
            })
            .verifyComplete();
    }

    @Test
    void enroll_lifecycleEvaluationFails_doesNotRollBack() {
        // A broken Drools KieBase (or any failure inside the lifecycle hook)
        // MUST NOT bomb the enrolment — the member row is already inserted
        // and the rule outcome is best-effort.
        var actorId = UUID.randomUUID().toString();
        var request = new CreateMemberRequest(
            "John", "Doe", LocalDate.of(1990, 1, 15), "male",
            "63-1234567", "john@example.com", null, null, null, UUID.randomUUID(), null
        );

        when(memberRepository.existsByMemberNumber(any())).thenReturn(Mono.just(false));
        when(memberRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        when(eventPublisher.publishMemberEnrolled(any(), any(), any())).thenReturn(Mono.empty());
        when(keycloakSyncService.createUser(any(), any(), any(), any(), any())).thenReturn(Mono.just("kc-user-id"));
        // Override the lenient no-op from @BeforeEach
        when(lifecycleService.evaluateOnEnrollment(any()))
            .thenReturn(Mono.error(new RuntimeException("KieBase missing")));

        StepVerifier.create(
            memberService.enroll(request, actorId, "actor@test.example")
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
            .assertNext(member -> assertThat(member.getStatus()).isEqualTo("enrolled"))
            .verifyComplete();

        verify(auditPublisher).publish(any());
        verify(eventPublisher).publishMemberEnrolled(any(), any(), any());
    }

    @Test
    void enroll_auditPublishFails_doesNotRollBack() {
        // Audit + event publish are best-effort from the caller's perspective.
        // A Kafka outage must not surface as a 5xx — the member is enrolled
        // and the failure is logged server-side.
        var actorId = UUID.randomUUID().toString();
        var request = new CreateMemberRequest(
            "John", "Doe", LocalDate.of(1990, 1, 15), "male",
            "63-1234567", "john@example.com", null, null, null, UUID.randomUUID(), null
        );

        when(memberRepository.existsByMemberNumber(any())).thenReturn(Mono.just(false));
        when(memberRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.error(new RuntimeException("Kafka down")));
        // The .then() arg is evaluated eagerly even though it's never subscribed
        // once audit errors — stub it to keep Mockito from returning null.
        when(eventPublisher.publishMemberEnrolled(any(), any(), any())).thenReturn(Mono.empty());
        when(keycloakSyncService.createUser(any(), any(), any(), any(), any())).thenReturn(Mono.just("kc-user-id"));

        StepVerifier.create(
            memberService.enroll(request, actorId, "actor@test.example")
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
            .assertNext(member -> assertThat(member.getStatus()).isEqualTo("enrolled"))
            .verifyComplete();
    }

    @Test
    void enroll_normalisesEnrollmentDateToFirstOfMonth() {
        // Operators may submit any day within the target month — the DTO
        // must snap to day-1 so contribution cycles have a stable anchor.
        var actorId = UUID.randomUUID().toString();
        var midMonth = LocalDate.of(2026, 6, 19);
        var request = new CreateMemberRequest(
            "John", "Doe", LocalDate.of(1990, 1, 15), "male",
            "63-1234567", "john@example.com", null, null, null, UUID.randomUUID(), midMonth
        );

        when(memberRepository.existsByMemberNumber(any())).thenReturn(Mono.just(false));
        when(memberRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        when(eventPublisher.publishMemberEnrolled(any(), any(), any())).thenReturn(Mono.empty());
        when(keycloakSyncService.createUser(any(), any(), any(), any(), any())).thenReturn(Mono.just("kc-user-id"));

        StepVerifier.create(
            memberService.enroll(request, actorId, "actor@test.example")
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
            .assertNext(member -> assertThat(member.getEnrollmentDate())
                .isEqualTo(LocalDate.of(2026, 6, 1)))
            .verifyComplete();
    }

    @Test
    void activate_existingMember_setsStatusActive() {
        var member = createTestMember();
        var id = member.getId();
        var actorId = UUID.randomUUID().toString();

        when(memberRepository.findById(id)).thenReturn(Mono.just(member));
        when(memberRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        when(eventPublisher.publishMemberLifecycle(any(), any())).thenReturn(Mono.empty());

        StepVerifier.create(
            memberService.activate(id, actorId, "actor@test.example")
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
            .assertNext(result -> assertThat(result.getStatus()).isEqualTo("active"))
            .verifyComplete();

        verify(auditPublisher).publish(any());
    }

    @Test
    void suspend_existingMember_setsStatusSuspended() {
        var member = createTestMember();
        var id = member.getId();
        var actorId = UUID.randomUUID().toString();

        when(memberRepository.findById(id)).thenReturn(Mono.just(member));
        when(memberRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        when(eventPublisher.publishMemberLifecycle(any(), any())).thenReturn(Mono.empty());

        StepVerifier.create(
            memberService.suspend(id, actorId, "actor@test.example")
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
            .assertNext(result -> assertThat(result.getStatus()).isEqualTo("suspended"))
            .verifyComplete();

        verify(auditPublisher).publish(any());
        verify(eventPublisher).publishMemberLifecycle(any(), any());
    }

    @Test
    void terminate_existingMember_setsStatusTerminated() {
        var member = createTestMember();
        var id = member.getId();
        var actorId = UUID.randomUUID().toString();

        when(memberRepository.findById(id)).thenReturn(Mono.just(member));
        when(memberRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        when(eventPublisher.publishMemberLifecycle(any(), any())).thenReturn(Mono.empty());

        StepVerifier.create(
            memberService.terminate(id, actorId, "actor@test.example")
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
            .assertNext(result -> {
                assertThat(result.getStatus()).isEqualTo("terminated");
                assertThat(result.getTerminationDate()).isNotNull();
            })
            .verifyComplete();

        verify(auditPublisher).publish(any());
        verify(eventPublisher).publishMemberLifecycle(any(), any());
    }

    private Member createTestMember() {
        var member = new Member();
        member.setId(UUID.randomUUID());
        member.setMemberNumber("MBR-123456");
        member.setFirstName("John");
        member.setLastName("Doe");
        member.setDateOfBirth(LocalDate.of(1990, 1, 15));
        member.setGender("male");
        member.setEmail("john@example.com");
        member.setStatus("enrolled");
        member.setEnrollmentDate(LocalDate.now());
        member.setCreatedAt(Instant.now());
        member.setUpdatedAt(Instant.now());
        member.setCreatedBy(UUID.randomUUID());
        member.setUpdatedBy(UUID.randomUUID());
        return member;
    }
}

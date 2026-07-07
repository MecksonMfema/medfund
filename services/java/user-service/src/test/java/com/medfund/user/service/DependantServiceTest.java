package com.medfund.user.service;

import com.medfund.shared.audit.AuditPublisher;
import com.medfund.user.dto.CreateDependantRequest;
import com.medfund.user.dto.UpdateDependantRequest;
import com.medfund.user.entity.Dependant;
import com.medfund.user.exception.DependantNotFoundException;
import com.medfund.user.repository.DependantRepository;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DependantServiceTest {

    @Mock
    private DependantRepository dependantRepository;

    @Mock
    private AuditPublisher auditPublisher;

    @Mock
    private R2dbcEntityTemplate r2dbcTemplate;

    @Mock
    private MemberSchemeLookup memberSchemeLookup;

    @Mock
    private com.medfund.user.repository.MemberRepository memberRepository;

    @Mock
    private MemberNumberService memberNumberService;

    @Mock
    private AgeGroupResolver ageGroupResolver;

    @InjectMocks
    private DependantService dependantService;

    @BeforeEach
    void stubInsert() {
        lenient().when(r2dbcTemplate.insert(any(Dependant.class))).thenAnswer(inv -> {
            Dependant d = inv.getArgument(0);
            if (d.getId() == null) d.setId(UUID.randomUUID());
            return Mono.just(d);
        });
        // MemberSchemeLookup is queried on create so the dependant inherits
        // the primary member's scheme — stub as empty (no scheme) for these
        // tests since none of them assert on the resulting scheme_id column.
        lenient().when(memberSchemeLookup.schemeIdOf(any())).thenReturn(Mono.empty());
        // create() looks up the primary member for age-group / status
        // context. Stub as a minimal Member so downstream logic doesn't NPE.
        lenient().when(memberRepository.findById(any(UUID.class))).thenAnswer(inv -> {
            var m = new com.medfund.user.entity.Member();
            m.setId(inv.getArgument(0));
            m.setStatus("active");
            m.setMemberNumber("MBR-000001");
            return Mono.just(m);
        });
        // Number-issue + age-group resolution both run on create — stub as
        // no-ops so the test focuses on the persist + audit path.
        lenient().when(memberNumberService.nextDependantNumber(any()))
                .thenAnswer(inv -> Mono.just("DEP-000001"));
        lenient().when(ageGroupResolver.resolveForSchemeAndDob(any(), any()))
                .thenReturn(Mono.empty());
    }

    @Test
    void findByMemberId_returnsDependants() {
        var memberId = UUID.randomUUID();
        var dep1 = createTestDependant(memberId);
        var dep2 = createTestDependant(memberId);
        dep2.setFirstName("Jane");

        when(dependantRepository.findByMemberId(memberId)).thenReturn(Flux.just(dep1, dep2));

        StepVerifier.create(dependantService.findByMemberId(memberId))
            .expectNext(dep1)
            .expectNext(dep2)
            .verifyComplete();

        verify(dependantRepository).findByMemberId(memberId);
    }

    @Test
    void findById_existing_returnsDependant() {
        var dependant = createTestDependant(UUID.randomUUID());
        var id = dependant.getId();

        when(dependantRepository.findById(id)).thenReturn(Mono.just(dependant));

        StepVerifier.create(dependantService.findById(id))
            .assertNext(result -> {
                assertThat(result.getId()).isEqualTo(id);
                assertThat(result.getFirstName()).isEqualTo("Sarah");
            })
            .verifyComplete();
    }

    @Test
    void findById_nonExisting_throwsNotFound() {
        var id = UUID.randomUUID();

        when(dependantRepository.findById(id)).thenReturn(Mono.empty());

        StepVerifier.create(dependantService.findById(id))
            .expectError(DependantNotFoundException.class)
            .verify();
    }

    @Test
    void create_validRequest_createsDependant() {
        var actorId = UUID.randomUUID().toString();
        var memberId = UUID.randomUUID();
        var request = new CreateDependantRequest(
            memberId, "Sarah", "Doe", LocalDate.of(2015, 6, 20),
            "female", "child", null,
            null, null, null,  // billingOverrideAmount / reason / effectiveFrom
            null,              // billingAgeGroupId
            null               // enrollmentDate (V047) — defaults to 1st of current month
        );

        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
            dependantService.create(request, actorId, "actor@test.example")
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
            .assertNext(dependant -> {
                assertThat(dependant.getStatus()).isEqualTo("active");
                assertThat(dependant.getFirstName()).isEqualTo("Sarah");
                assertThat(dependant.getLastName()).isEqualTo("Doe");
                assertThat(dependant.getRelationship()).isEqualTo("child");
                assertThat(dependant.getMemberId()).isEqualTo(memberId);
            })
            .verifyComplete();

        verify(r2dbcTemplate).insert(any(Dependant.class));
        verify(auditPublisher).publish(any());
    }

    // ------------------------------------------------------------------
    // Custom-premium on create (V030 INDIVIDUAL model).
    //
    // Backend must accept the override triple on the dependant-create
    // path so operators can capture it in one round-trip from the
    // inline collapsible on the member detail page.
    // ------------------------------------------------------------------

    @Test
    void create_withCustomPremium_persistsOverrideTriple() {
        var actorId = UUID.randomUUID().toString();
        var memberId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("40.00");
        LocalDate effectiveFrom = LocalDate.of(2026, 8, 1);
        var request = new CreateDependantRequest(
            memberId, "Sarah", "Doe", LocalDate.of(2015, 6, 20),
            "female", "child", null,
            amount, "student rate", effectiveFrom,
            null,  // billingAgeGroupId
            null   // enrollmentDate
        );

        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
            dependantService.create(request, actorId, "actor@test.example")
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
            .assertNext(dependant -> {
                assertThat(dependant.getBillingOverrideAmount()).isEqualByComparingTo(amount);
                assertThat(dependant.getBillingOverrideReason()).isEqualTo("student rate");
                assertThat(dependant.getBillingOverrideEffectiveFrom()).isEqualTo(effectiveFrom);
            })
            .verifyComplete();
    }

    @Test
    void create_amountWithoutEffectiveFrom_failsFastWith400() {
        // Half-filled triple must fail cleanly. Same gate as the member
        // enrolment path — a DB CHECK violation would surface as an
        // opaque 500 otherwise.
        var actorId = UUID.randomUUID().toString();
        var request = new CreateDependantRequest(
            UUID.randomUUID(), "Sarah", "Doe", LocalDate.of(2015, 6, 20),
            "female", "child", null,
            new BigDecimal("40.00"), "student rate", /* effectiveFrom */ null,
            null,  // billingAgeGroupId
            null   // enrollmentDate
        );

        StepVerifier.create(
            dependantService.create(request, actorId, "actor@test.example")
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
            .expectErrorSatisfies(err -> {
                assertThat(err).isInstanceOf(IllegalArgumentException.class);
                assertThat(err.getMessage()).contains("billingOverrideEffectiveFrom");
            })
            .verify();

        verify(r2dbcTemplate, never()).insert(any(Dependant.class));
    }

    @Test
    void create_withoutCustomPremium_leavesOverrideFieldsNull() {
        var actorId = UUID.randomUUID().toString();
        var memberId = UUID.randomUUID();
        var request = new CreateDependantRequest(
            memberId, "Sarah", "Doe", LocalDate.of(2015, 6, 20),
            "female", "child", null,
            null, null, null,
            null,  // billingAgeGroupId
            null   // enrollmentDate
        );

        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
            dependantService.create(request, actorId, "actor@test.example")
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
            .assertNext(dependant -> {
                assertThat(dependant.getBillingOverrideAmount()).isNull();
                assertThat(dependant.getBillingOverrideReason()).isNull();
                assertThat(dependant.getBillingOverrideEffectiveFrom()).isNull();
            })
            .verifyComplete();
    }

    @Test
    void update_changesOnlyProvidedFields_andEmitsAudit() {
        var dependant = createTestDependant(UUID.randomUUID());
        var id = dependant.getId();
        var actorId = UUID.randomUUID().toString();
        var request = new UpdateDependantRequest("Janet", null, null, null, "spouse", null, null, null, null, null, null);

        when(dependantRepository.findById(id)).thenReturn(Mono.just(dependant));
        when(dependantRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
            dependantService.update(id, request, actorId, "actor@test.example")
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
            .assertNext(result -> {
                assertThat(result.getFirstName()).isEqualTo("Janet");
                assertThat(result.getRelationship()).isEqualTo("spouse");
                // Unchanged fields preserved.
                assertThat(result.getLastName()).isEqualTo("Doe");
                assertThat(result.getStatus()).isEqualTo("active");
            })
            .verifyComplete();

        verify(auditPublisher).publish(any());
    }

    @Test
    void update_setsBillingAgeGroupId_whenProvided() {
        // Dependant-side twin of the member test — DependantService.update
        // line 157-159 has no positive assertion. Regression here
        // would silently drop every age-band override set post-add on a
        // dependant (typical use: child with disability moved to a
        // different band after their birthday should have aged them out).
        var dependant = createTestDependant(UUID.randomUUID());
        var id = dependant.getId();
        var actorId = UUID.randomUUID().toString();
        UUID newAgeBand = UUID.randomUUID();
        var request = new UpdateDependantRequest(
            null, null, null, null, null, null,
            null, null, null,
            newAgeBand,
            null // enrollmentDate
        );

        when(dependantRepository.findById(id)).thenReturn(Mono.just(dependant));
        when(dependantRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
            dependantService.update(id, request, actorId, "actor@test.example")
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
            .assertNext(saved -> assertThat(saved.getBillingAgeGroupId()).isEqualTo(newAgeBand))
            .verifyComplete();
    }

    @Test
    void update_leavesBillingAgeGroupId_whenRequestIsNull() {
        // "null means no change" — the update path must not clobber an
        // existing override with null. A regression flipping this to
        // unconditional set would erase every existing age-band
        // override on an unrelated update (e.g. gender/relationship).
        var dependant = createTestDependant(UUID.randomUUID());
        UUID existingBand = UUID.randomUUID();
        dependant.setBillingAgeGroupId(existingBand);
        var id = dependant.getId();
        var actorId = UUID.randomUUID().toString();
        var request = new UpdateDependantRequest(
            "Janet", null, null, null, "spouse", null,
            null, null, null,
            null, // billingAgeGroupId — omitted means "no change"
            null  // enrollmentDate — omitted means "no change"
        );

        when(dependantRepository.findById(id)).thenReturn(Mono.just(dependant));
        when(dependantRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
            dependantService.update(id, request, actorId, "actor@test.example")
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
            .assertNext(saved -> assertThat(saved.getBillingAgeGroupId()).isEqualTo(existingBand))
            .verifyComplete();
    }

    @Test
    void update_nonExisting_throwsNotFound() {
        var id = UUID.randomUUID();
        var request = new UpdateDependantRequest("X", null, null, null, null, null, null, null, null, null, null);
        when(dependantRepository.findById(id)).thenReturn(Mono.empty());

        StepVerifier.create(
            dependantService.update(id, request, UUID.randomUUID().toString(), "actor@test.example")
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
            .expectError(DependantNotFoundException.class)
            .verify();
    }

    @Test
    void deactivate_withOperatorPickedDate_snapsToEndOfMonth() {
        // V046 + feedback_effective_date_snap: dependants are never deleted
        // — this is the terminal soft-transition. The service snaps the
        // operator's effective date to end-of-month so the dependant stays
        // billable through the whole cycle it ends in (belt-and-braces with
        // @EndOfMonth on the DTO).
        var dependant = createTestDependant(UUID.randomUUID());
        var id = dependant.getId();
        var actorId = UUID.randomUUID().toString();
        LocalDate midMonth = LocalDate.of(2026, 7, 15);
        LocalDate expectedSnapped = LocalDate.of(2026, 7, 31);

        when(dependantRepository.findById(id)).thenReturn(Mono.just(dependant));
        when(dependantRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
            dependantService.deactivate(id, midMonth, actorId, "actor@test.example")
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
            .assertNext(result -> {
                assertThat(result.getStatus()).isEqualTo("deactivated");
                assertThat(result.getDeactivationEffectiveDate()).isEqualTo(expectedSnapped);
            })
            .verifyComplete();

        verify(auditPublisher).publish(any());
    }

    @Test
    void deactivate_nonExisting_throwsNotFound() {
        // Parallel to update_nonExisting_throwsNotFound — the
        // switchIfEmpty(...) branch must fire cleanly rather than
        // NPE inside the flatMap. Critical because deactivation is
        // a destructive-looking action; a 500 here would leave the
        // operator uncertain whether the row was mutated.
        var id = UUID.randomUUID();
        when(dependantRepository.findById(id)).thenReturn(Mono.empty());

        StepVerifier.create(
            dependantService.deactivate(id, LocalDate.of(2026, 7, 15),
                    UUID.randomUUID().toString(), "actor@test.example")
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
            .expectError(DependantNotFoundException.class)
            .verify();

        // No write, no audit — cleanly bail.
        verify(dependantRepository, never()).save(any());
        verify(auditPublisher, never()).publish(any());
    }

    @Test
    void deactivate_nullEffectiveDate_defaultsToEndOfMonth() {
        // The controller allows an omitted body — service defaults to
        // today, then snaps to end-of-month per
        // feedback_effective_date_snap so the dependant stays billable
        // through the whole current cycle.
        var dependant = createTestDependant(UUID.randomUUID());
        var id = dependant.getId();
        var today = LocalDate.now();
        var expectedEom = today.withDayOfMonth(today.lengthOfMonth());

        when(dependantRepository.findById(id)).thenReturn(Mono.just(dependant));
        when(dependantRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
            dependantService.deactivate(id, null,
                    UUID.randomUUID().toString(), "actor@test.example")
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
            .assertNext(result -> {
                assertThat(result.getStatus()).isEqualTo("deactivated");
                assertThat(result.getDeactivationEffectiveDate()).isEqualTo(expectedEom);
            })
            .verifyComplete();
    }

    private Dependant createTestDependant(UUID memberId) {
        var dependant = new Dependant();
        dependant.setId(UUID.randomUUID());
        dependant.setMemberId(memberId);
        dependant.setFirstName("Sarah");
        dependant.setLastName("Doe");
        dependant.setDateOfBirth(LocalDate.of(2015, 6, 20));
        dependant.setGender("female");
        dependant.setRelationship("child");
        dependant.setStatus("active");
        dependant.setCreatedAt(Instant.now());
        dependant.setUpdatedAt(Instant.now());
        dependant.setCreatedBy(UUID.randomUUID());
        dependant.setUpdatedBy(UUID.randomUUID());
        return dependant;
    }
}

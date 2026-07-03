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

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
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

    @InjectMocks
    private DependantService dependantService;

    @BeforeEach
    void stubInsert() {
        lenient().when(r2dbcTemplate.insert(any(Dependant.class))).thenAnswer(inv -> {
            Dependant d = inv.getArgument(0);
            if (d.getId() == null) d.setId(UUID.randomUUID());
            return Mono.just(d);
        });
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
            "female", "child", null
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

    @Test
    void update_changesOnlyProvidedFields_andEmitsAudit() {
        var dependant = createTestDependant(UUID.randomUUID());
        var id = dependant.getId();
        var actorId = UUID.randomUUID().toString();
        var request = new UpdateDependantRequest("Janet", null, null, null, "spouse", null, null, null, null);

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
    void update_nonExisting_throwsNotFound() {
        var id = UUID.randomUUID();
        var request = new UpdateDependantRequest("X", null, null, null, null, null, null, null, null);
        when(dependantRepository.findById(id)).thenReturn(Mono.empty());

        StepVerifier.create(
            dependantService.update(id, request, UUID.randomUUID().toString(), "actor@test.example")
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
            .expectError(DependantNotFoundException.class)
            .verify();
    }

    @Test
    void remove_existingDependant_setsStatusRemoved() {
        var dependant = createTestDependant(UUID.randomUUID());
        var id = dependant.getId();
        var actorId = UUID.randomUUID().toString();

        when(dependantRepository.findById(id)).thenReturn(Mono.just(dependant));
        when(dependantRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
            dependantService.remove(id, actorId, "actor@test.example")
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
            .assertNext(result -> assertThat(result.getStatus()).isEqualTo("removed"))
            .verifyComplete();

        verify(auditPublisher).publish(any());
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

package com.medfund.user.service;

import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import com.medfund.user.dto.CreateDependantRequest;
import com.medfund.user.dto.UpdateDependantRequest;
import com.medfund.user.entity.Dependant;
import com.medfund.user.exception.DependantNotFoundException;
import com.medfund.user.repository.DependantRepository;
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
public class DependantService {

    private final DependantRepository dependantRepository;
    private final R2dbcEntityTemplate r2dbcTemplate;
    private final AuditPublisher auditPublisher;

    public Flux<Dependant> findByMemberId(UUID memberId) {
        return dependantRepository.findByMemberId(memberId);
    }

    public Mono<Dependant> findById(UUID id) {
        return dependantRepository.findById(id)
            .switchIfEmpty(Mono.error(new DependantNotFoundException(id)));
    }

    @Transactional
    public Mono<Dependant> create(CreateDependantRequest request, String actorId, String actorEmail) {
        var dependant = new Dependant();
        // id NOT set — let PostgreSQL generate via DEFAULT gen_random_uuid()
        dependant.setMemberId(request.memberId());
        dependant.setFirstName(request.firstName());
        dependant.setLastName(request.lastName());
        dependant.setDateOfBirth(request.dateOfBirth());
        dependant.setGender(request.gender());
        dependant.setRelationship(request.relationship());
        dependant.setNationalId(request.nationalId());
        dependant.setStatus("active");
        dependant.setCreatedAt(Instant.now());
        dependant.setUpdatedAt(Instant.now());
        dependant.setCreatedBy(UUID.fromString(actorId));
        dependant.setUpdatedBy(UUID.fromString(actorId));

        return r2dbcTemplate.insert(dependant)
            .flatMap(saved -> Mono.deferContextual(ctx -> {
                String tenantId = TenantContext.get(ctx);
                var event = AuditEvent.create(
                    tenantId != null ? tenantId : "unknown", "Dependant", saved.getId().toString(),
                    saved.getFirstName() + " " + saved.getLastName(),
                    "CREATE", actorId, actorEmail, null,
                    Map.of("firstName", saved.getFirstName(), "lastName", saved.getLastName(),
                        "relationship", saved.getRelationship(), "memberId", saved.getMemberId().toString()),
                    new String[]{"firstName", "lastName", "relationship"},
                    UUID.randomUUID().toString()
                );
                return auditPublisher.publish(event).thenReturn(saved);
            }));
    }

    @Transactional
    public Mono<Dependant> update(UUID id, UpdateDependantRequest request, String actorId, String actorEmail) {
        return dependantRepository.findById(id)
            .switchIfEmpty(Mono.error(new DependantNotFoundException(id)))
            .flatMap(existing -> {
                Map<String, Object> oldValue = Map.of(
                    "firstName", existing.getFirstName() != null ? existing.getFirstName() : "",
                    "lastName",  existing.getLastName()  != null ? existing.getLastName()  : "",
                    "relationship", existing.getRelationship() != null ? existing.getRelationship() : ""
                );

                if (request.firstName()    != null) existing.setFirstName(request.firstName());
                if (request.lastName()     != null) existing.setLastName(request.lastName());
                if (request.dateOfBirth()  != null) existing.setDateOfBirth(request.dateOfBirth());
                if (request.gender()       != null) existing.setGender(request.gender());
                if (request.relationship() != null) existing.setRelationship(request.relationship());
                if (request.nationalId()   != null) existing.setNationalId(request.nationalId());
                existing.setUpdatedAt(Instant.now());
                existing.setUpdatedBy(UUID.fromString(actorId));

                return dependantRepository.save(existing)
                    .flatMap(saved -> Mono.deferContextual(ctx -> {
                        String tenantId = TenantContext.get(ctx);
                        var event = AuditEvent.create(
                            tenantId != null ? tenantId : "unknown", "Dependant", saved.getId().toString(),
                            saved.getFirstName() + " " + saved.getLastName(),
                            "UPDATE", actorId, actorEmail, oldValue,
                            Map.of("firstName", saved.getFirstName(), "lastName", saved.getLastName(),
                                "relationship", saved.getRelationship()),
                            new String[]{"firstName", "lastName", "relationship"},
                            UUID.randomUUID().toString()
                        );
                        return auditPublisher.publish(event).thenReturn(saved);
                    }));
            });
    }

    @Transactional
    public Mono<Dependant> remove(UUID id, String actorId, String actorEmail) {
        return dependantRepository.findById(id)
            .switchIfEmpty(Mono.error(new DependantNotFoundException(id)))
            .flatMap(existing -> {
                existing.setStatus("removed");
                existing.setUpdatedAt(Instant.now());
                existing.setUpdatedBy(UUID.fromString(actorId));
                return dependantRepository.save(existing);
            })
            .flatMap(saved -> Mono.deferContextual(ctx -> {
                String tenantId = TenantContext.get(ctx);
                var event = AuditEvent.create(
                    tenantId != null ? tenantId : "unknown", "Dependant", saved.getId().toString(),
                    saved.getFirstName() + " " + saved.getLastName(),
                    "UPDATE", actorId, actorEmail,
                    Map.of("status", "active"),
                    Map.of("status", "removed"),
                    new String[]{"status"},
                    UUID.randomUUID().toString()
                );
                return auditPublisher.publish(event).thenReturn(saved);
            }));
    }

    public Mono<Void> flagOverAgeDependants() {
        int maxAge = 21;
        return dependantRepository.findByMemberIdAndStatus(null, "active")
            .switchIfEmpty(dependantRepository.findAll())
            .filter(d -> d.getStatus() != null && d.getStatus().equals("active"))
            .filter(d -> {
                if (d.getDateOfBirth() == null) return false;
                int age = java.time.Period.between(d.getDateOfBirth(), java.time.LocalDate.now()).getYears();
                return age > maxAge;
            })
            .flatMap(d -> {
                d.setStatus("over_age");
                d.setUpdatedAt(java.time.Instant.now());
                return dependantRepository.save(d);
            })
            .then();
    }
}

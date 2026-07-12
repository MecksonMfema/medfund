package com.medfund.user.service;

import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import com.medfund.user.dto.CreateDependantRequest;
import com.medfund.user.dto.UpdateDependantRequest;
import com.medfund.user.entity.Dependant;
import com.medfund.user.exception.DependantNotFoundException;
import com.medfund.user.exception.MemberNotFoundException;
import com.medfund.user.repository.DependantRepository;
import com.medfund.user.repository.MemberRepository;
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
    private final MemberRepository memberRepository;
    private final R2dbcEntityTemplate r2dbcTemplate;
    private final AuditPublisher auditPublisher;
    private final MemberSchemeLookup memberSchemeLookup;
    private final AgeGroupResolver ageGroupResolver;
    private final MemberNumberService memberNumberService;
    private final UserEventPublisher eventPublisher;

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
        // Status derives from the enrolment date (V048), mirroring the
        // member enrol flow:
        //   * enrollmentDate <= today → cover has started → 'active'.
        //   * enrollmentDate > today → 'enrolled' until the daily
        //     SCHEDULED_STATUS_ROLL job flips them on-date. The resolver
        //     also gates on d.enrollment_date so a stray 'active' would
        //     not bill early, but the status here is what the operator
        //     sees on the roster — showing 'active' before cover starts
        //     misleads them.
        java.time.LocalDate enrollment = request.enrollmentDateOrDefault();
        dependant.setEnrollmentDate(enrollment);
        dependant.setStatus(enrollment.isAfter(java.time.LocalDate.now()) ? "enrolled" : "active");
        // Custom-premium triple at creation (V030). Same amount +
        // effective_from consistency rule as the update path. STANDARD-
        // model tenants never send these fields; the frontend gates
        // the section on tenant.pricingModel.
        if (request.billingOverrideAmount() != null) {
            if (request.billingOverrideEffectiveFrom() == null) {
                return Mono.error(new IllegalArgumentException(
                        "billingOverrideEffectiveFrom is required when billingOverrideAmount is set"));
            }
            dependant.setBillingOverrideAmount(request.billingOverrideAmount());
            dependant.setBillingOverrideReason(request.billingOverrideReason());
            dependant.setBillingOverrideEffectiveFrom(request.billingOverrideEffectiveFrom());
        }
        // Manual age-group override at creation — accepted for every
        // pricing_model. Applies whenever set (resolver checks it
        // unconditionally, see HealthCandidateResolver).
        if (request.billingAgeGroupId() != null) {
            dependant.setBillingAgeGroupId(request.billingAgeGroupId());
        }
        dependant.setCreatedAt(Instant.now());
        dependant.setUpdatedAt(Instant.now());
        dependant.setCreatedBy(UUID.fromString(actorId));
        dependant.setUpdatedBy(UUID.fromString(actorId));

        // Stamp the canonical age bucket on the dependant by joining
        // through the parent member's scheme. Same data-gap semantics as
        // for members — if no band covers their age, ageGroupId stays
        // null and surfaces later as a billing issue rather than being
        // silently coerced to the youngest/oldest bucket.
        Mono<Void> stampAgeGroup = memberSchemeLookup.schemeIdOf(dependant.getMemberId())
                .flatMap(schemeId -> ageGroupResolver.resolveForSchemeAndDob(
                        schemeId, dependant.getDateOfBirth()))
                .doOnNext(dependant::setAgeGroupId)
                .then();

        // Issue a tenant-configured member_number — INDEPENDENT scheme
        // yields "DEP-XXXXXX", SHARED_WITH_SUFFIX yields the parent's
        // base + monotonically-increasing "-NN" suffix. Loads the parent
        // member first to feed its number into the suffix lookup.
        Mono<Void> stampMemberNumber = memberRepository.findById(dependant.getMemberId())
                .switchIfEmpty(Mono.error(new MemberNotFoundException(dependant.getMemberId())))
                .flatMap(memberNumberService::nextDependantNumber)
                .doOnNext(dependant::setMemberNumber)
                .then();

        return stampAgeGroup
            .then(stampMemberNumber)
            .then(r2dbcTemplate.insert(dependant))
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
                // Fire a DEPENDANT_ENROLLED event so the contributions
                // service can post a LATE_ENROLMENT_CHARGE when the
                // effective date lands in an already-billed period.
                // Best-effort — a failed publish shouldn't roll the
                // insert back; the daily arrears sweep is the safety
                // net if the event never gets consumed.
                Mono<Void> emitEnrolled = memberRepository.findById(saved.getMemberId())
                        .flatMap(parent -> eventPublisher.publishDependantEnrolled(
                                saved.getId().toString(),
                                saved.getMemberNumber(),
                                saved.getMemberId().toString(),
                                parent.getGroupId()  != null ? parent.getGroupId().toString()  : null,
                                parent.getSchemeId() != null ? parent.getSchemeId().toString() : null,
                                saved.getEnrollmentDate() != null ? saved.getEnrollmentDate().toString() : null,
                                saved.getDateOfBirth() != null ? saved.getDateOfBirth().toString() : null))
                        .onErrorResume(e -> {
                            log.warn("DependantEnrolled publish failed for {}: {}",
                                    saved.getId(), e.getMessage());
                            return Mono.empty();
                        });
                return auditPublisher.publish(event).then(emitEnrolled).thenReturn(saved);
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

                // Individual-pricing override (V030). Same three-way
                // semantics as Member: setting amount requires
                // effective_from; clearing requires the dedicated
                // /clear-billing-override endpoint.
                if (request.billingOverrideAmount() != null) {
                    if (request.billingOverrideEffectiveFrom() == null) {
                        throw new IllegalArgumentException(
                                "billingOverrideEffectiveFrom is required when billingOverrideAmount is set");
                    }
                    existing.setBillingOverrideAmount(request.billingOverrideAmount());
                    existing.setBillingOverrideReason(request.billingOverrideReason());
                    existing.setBillingOverrideEffectiveFrom(request.billingOverrideEffectiveFrom());
                }

                // Manual age-group override. Null on the request means
                // "no change"; clearing goes through the dedicated
                // /clear-billing-override endpoint.
                if (request.billingAgeGroupId() != null) {
                    existing.setBillingAgeGroupId(request.billingAgeGroupId());
                }
                // Enrollment date on update (V047). Partial-update
                // semantics: null request means "no change". Any
                // supplied date is snapped to the 1st of its month.
                if (request.enrollmentDate() != null) {
                    existing.setEnrollmentDate(request.enrollmentDate().withDayOfMonth(1));
                }

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

    /**
     * Null out the override fields so billing falls back to the
     * age-group price for this dependant. Audited as a normal
     * UPDATE; no-op when no override is set.
     */
    @Transactional
    public Mono<Dependant> clearBillingOverride(UUID id, String actorId, String actorEmail) {
        return dependantRepository.findById(id)
            .switchIfEmpty(Mono.error(new DependantNotFoundException(id)))
            .flatMap(existing -> {
                if (existing.getBillingOverrideAmount() == null) {
                    return Mono.just(existing);
                }
                var previousAmount = existing.getBillingOverrideAmount().toPlainString();
                existing.setBillingOverrideAmount(null);
                existing.setBillingOverrideReason(null);
                existing.setBillingOverrideEffectiveFrom(null);
                existing.setUpdatedAt(Instant.now());
                existing.setUpdatedBy(UUID.fromString(actorId));
                return dependantRepository.save(existing)
                    .flatMap(saved -> Mono.deferContextual(ctx -> {
                        String tenantId = TenantContext.get(ctx);
                        var event = AuditEvent.create(
                                tenantId != null ? tenantId : "unknown",
                                "Dependant", saved.getId().toString(),
                                saved.getFirstName() + " " + saved.getLastName(),
                                "UPDATE", actorId, actorEmail,
                                Map.of("billingOverrideAmount", previousAmount),
                                Map.of("billingOverrideAmount", "cleared"),
                                new String[]{"billingOverrideAmount"},
                                UUID.randomUUID().toString());
                        return auditPublisher.publish(event).thenReturn(saved);
                    }));
            });
    }

    /**
     * Deactivate a dependant with an effective date (V046). Dependants
     * are never hard-deleted — this is the terminal soft-transition.
     *
     * <p>Billing continues UP TO AND INCLUDING the cycle that contains
     * {@code effectiveDate} — the resolver's dependant WHERE clause
     * excludes rows where {@code deactivation_effective_date < periodStart}.
     * So a dependant deactivated 2026-07-15 is billed for July, dropped
     * from August.
     *
     * <p>{@code effectiveDate} defaults to today when null so the
     * operator can hit the button without a picker and get immediate
     * semantics.
     */
    @Transactional
    public Mono<Dependant> deactivate(UUID id, java.time.LocalDate effectiveDate,
                                       String actorId, String actorEmail) {
        // Snap to end-of-month (feedback_effective_date_snap) so the
        // dependant remains billable for the whole current cycle when the
        // operator defaults to today. Belt-and-braces with @EndOfMonth on
        // the DeactivateDependantRequest DTO.
        java.time.LocalDate rawResolved = effectiveDate != null ? effectiveDate : java.time.LocalDate.now();
        java.time.LocalDate resolved = com.medfund.shared.validation.DateSnaps.toEndOfMonth(rawResolved);
        return dependantRepository.findById(id)
            .switchIfEmpty(Mono.error(new DependantNotFoundException(id)))
            .flatMap(existing -> {
                String previousStatus = existing.getStatus();
                existing.setStatus("deactivated");
                existing.setDeactivationEffectiveDate(resolved);
                existing.setUpdatedAt(Instant.now());
                existing.setUpdatedBy(UUID.fromString(actorId));
                return dependantRepository.save(existing)
                    .flatMap(saved -> Mono.deferContextual(ctx -> {
                        String tenantId = TenantContext.get(ctx);
                        var event = AuditEvent.create(
                            tenantId != null ? tenantId : "unknown", "Dependant", saved.getId().toString(),
                            saved.getFirstName() + " " + saved.getLastName(),
                            "UPDATE", actorId, actorEmail,
                            Map.of("status", previousStatus != null ? previousStatus : "active"),
                            Map.of("status", "deactivated",
                                   "deactivationEffectiveDate", resolved.toString()),
                            new String[]{"status", "deactivationEffectiveDate"},
                            UUID.randomUUID().toString()
                        );
                        return auditPublisher.publish(event).thenReturn(saved);
                    }));
            });
    }

    /**
     * Flip a dependant from {@code enrolled} to {@code active}. Called
     * by the daily {@code SCHEDULED_STATUS_ROLL} job when a
     * future-dated enrolment reaches its effective date. No-op when
     * the row isn't in {@code enrolled} state.
     */
    @Transactional
    public Mono<Dependant> activate(UUID id, String actorId, String actorEmail) {
        return dependantRepository.findById(id)
            .switchIfEmpty(Mono.error(new DependantNotFoundException(id)))
            .flatMap(existing -> {
                if (!"enrolled".equals(existing.getStatus())) {
                    return Mono.just(existing); // already active / suspended / deactivated
                }
                String previousStatus = existing.getStatus();
                existing.setStatus("active");
                existing.setUpdatedAt(Instant.now());
                existing.setUpdatedBy(UUID.fromString(actorId));
                return dependantRepository.save(existing)
                    .flatMap(saved -> Mono.deferContextual(ctx -> {
                        String tenantId = TenantContext.get(ctx);
                        var event = AuditEvent.create(
                            tenantId != null ? tenantId : "unknown", "Dependant", saved.getId().toString(),
                            saved.getFirstName() + " " + saved.getLastName(),
                            "UPDATE", actorId, actorEmail,
                            Map.of("status", previousStatus),
                            Map.of("status", "active"),
                            new String[]{"status"},
                            UUID.randomUUID().toString()
                        );
                        return auditPublisher.publish(event).thenReturn(saved);
                    }));
            });
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

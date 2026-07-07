package com.medfund.user.service;

import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import com.medfund.user.dto.CreateGroupRequest;
import com.medfund.user.dto.UpdateGroupRequest;
import com.medfund.user.entity.Group;
import com.medfund.user.exception.GroupNotFoundException;
import com.medfund.user.repository.GroupLiaisonRepository;
import com.medfund.user.repository.GroupRepository;
import com.medfund.user.repository.MemberRepository;
import com.medfund.user.repository.StaffUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class GroupService {

    private static final String PLATFORM_REALM = "medfund-platform";
    private static final String GROUP_LIAISON_ROLE = "group_liaison";

    private final GroupRepository groupRepository;
    private final MemberRepository memberRepository;
    private final StaffUserRepository staffUserRepository;
    private final GroupLiaisonRepository groupLiaisonRepository;
    private final R2dbcEntityTemplate r2dbcTemplate;
    private final AuditPublisher auditPublisher;
    private final KeycloakSyncService keycloakSyncService;
    // Cascades group-lifecycle transitions to member rows. Split constructor
    // above stays unchanged — @RequiredArgsConstructor picks this up.
    private final MemberService memberService;
    private final UserEventPublisher eventPublisher;
    // Auto-generates the group's registration_number per the tenant's
    // configured shape (V125). Frontend no longer sends a user-supplied
    // value on create; any incoming value is ignored.
    private final GroupNumberService groupNumberService;

    public Flux<Group> findAll() {
        return groupRepository.findAllOrderByCreatedAtDesc();
    }

    public Mono<Group> findById(UUID id) {
        return groupRepository.findById(id)
            .switchIfEmpty(Mono.error(new GroupNotFoundException(id)));
    }

    public Flux<Group> findByStatus(String status) {
        return groupRepository.findByStatus(status);
    }

    public Flux<Group> search(String name) {
        return groupRepository.searchByName(name);
    }

    @Transactional
    public Mono<Group> create(CreateGroupRequest request, String actorId, String actorEmail) {
        // A group must reach the notification-service either via a liaison
        // or a fallback email — one or the other must be set. Both is fine;
        // neither leaves the recipient resolver with nothing to route to
        // and stalls every subsequent contribution statement (see
        // bug_public_prefix_silent_rollback for the outage this replaces).
        boolean kindMissing = request.liaisonKind() == null || request.liaisonKind().isBlank();
        boolean idMissing = request.liaisonUserId() == null;
        boolean liaisonMissing = kindMissing || idMissing;
        boolean emailMissing = request.email() == null || request.email().isBlank();
        if (liaisonMissing && emailMissing) {
            return Mono.error(new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "A group needs either a liaison (kind + user) or a contact email — both are missing"));
        }
        return validateLiaison(request.liaisonKind(), request.liaisonUserId())
            .then(grantLiaisonRole(request.liaisonKind(), request.liaisonUserId()))
            .then(groupNumberService.nextRegistrationNumber())
            .flatMap(registrationNumber -> {
                var group = new Group();
                // id NOT set — let PostgreSQL generate via DEFAULT gen_random_uuid()
                group.setName(request.name());
                // Registration number is issued server-side per the tenant's
                // configured shape (V125). Any value on the incoming request
                // is deliberately ignored — the frontend no longer collects
                // one, and honouring a user-supplied number would let two
                // operators land on the same string.
                group.setRegistrationNumber(registrationNumber);
                group.setAddress(request.address());
                group.setEmail(request.email());
                group.setLiaisonKind(request.liaisonKind());
                group.setLiaisonUserId(request.liaisonUserId());
                group.setStatus("active");
                group.setCreatedAt(Instant.now());
                group.setUpdatedAt(Instant.now());
                group.setCreatedBy(UUID.fromString(actorId));
                group.setUpdatedBy(UUID.fromString(actorId));

                return r2dbcTemplate.insert(group);
            })
            .flatMap(saved -> Mono.deferContextual(ctx -> {
                String tenantId = TenantContext.get(ctx);
                var event = AuditEvent.create(
                    tenantId != null ? tenantId : "unknown", "Group", saved.getId().toString(),
                    saved.getName(),
                    "CREATE", actorId, actorEmail, null,
                    Map.of("name", saved.getName(), "status", saved.getStatus()),
                    new String[]{"name", "status"},
                    UUID.randomUUID().toString()
                );
                return auditPublisher.publish(event).thenReturn(saved);
            }));
    }

    @Transactional
    public Mono<Group> update(UUID id, UpdateGroupRequest request, String actorId, String actorEmail) {
        return groupRepository.findById(id)
            .switchIfEmpty(Mono.error(new GroupNotFoundException(id)))
            .flatMap(existing -> applyLiaisonUpdate(existing, request).thenReturn(existing))
            .flatMap(existing -> grantLiaisonRole(request.liaisonKind(), request.liaisonUserId()).thenReturn(existing))
            .flatMap(existing -> {
                if (request.name() != null) existing.setName(request.name());
                // registration_number is server-issued and immutable —
                // silently ignore any incoming value on update. A hard
                // reject would break tenants whose old edit forms still
                // POST the whole entity; ignoring lets them roll
                // forward safely.
                if (request.address() != null) existing.setAddress(request.address());
                if (request.email() != null) existing.setEmail(request.email().isBlank() ? null : request.email());
                // Post-update the group must still be reachable — liaison OR
                // email. Blocks an operator from clearing both in one PATCH
                // and silently orphaning the group from the statement flow.
                boolean hasLiaison = existing.getLiaisonKind() != null && existing.getLiaisonUserId() != null;
                boolean hasEmail = existing.getEmail() != null && !existing.getEmail().isBlank();
                if (!hasLiaison && !hasEmail) {
                    return Mono.<Group>error(new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "A group needs either a liaison (kind + user) or a contact email — this update would leave both empty"));
                }
                existing.setUpdatedAt(Instant.now());
                existing.setUpdatedBy(UUID.fromString(actorId));

                return groupRepository.save(existing)
                    .flatMap(saved -> Mono.deferContextual(ctx -> {
                        String tenantId = TenantContext.get(ctx);
                        var event = AuditEvent.create(
                            tenantId != null ? tenantId : "unknown", "Group", saved.getId().toString(),
                            saved.getName(),
                            "UPDATE", actorId, actorEmail, null,
                            Map.of("name", saved.getName()),
                            new String[]{"name", "registrationNumber", "address"},
                            UUID.randomUUID().toString()
                        );
                        return auditPublisher.publish(event).thenReturn(saved);
                    }));
            });
    }

    /**
     * Resolve the liaison fields on an update request against the existing
     * group, validating any change. The legal shapes are:
     *   - null kind + null id                  → no liaison change
     *   - "CLEAR"                              → drop the liaison
     *   - "MEMBER"/"STAFF"/"LIAISON" + id      → assign; verify FK target exists
     * Anything else (e.g. kind without id) is rejected with 422.
     */
    private Mono<Void> applyLiaisonUpdate(Group existing, UpdateGroupRequest request) {
        String kind = request.liaisonKind();
        UUID id = request.liaisonUserId();
        if (kind == null && id == null) return Mono.empty();
        if ("CLEAR".equals(kind)) {
            if (id != null) {
                return Mono.error(new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "liaisonKind=CLEAR must not be accompanied by a liaisonUserId"));
            }
            existing.setLiaisonKind(null);
            existing.setLiaisonUserId(null);
            return Mono.empty();
        }
        return validateLiaison(kind, id).then(Mono.fromRunnable(() -> {
            existing.setLiaisonKind(kind);
            existing.setLiaisonUserId(id);
        }));
    }

    /**
     * Validate a (kind, id) pair: both supplied, kind is MEMBER or STAFF,
     * and the FK target exists in the corresponding table. Pair-consistency
     * is also enforced at the schema level via CHECK constraints (V023);
     * doing it here surfaces a friendlier 422 response with a message.
     */
    private Mono<Void> validateLiaison(String kind, UUID id) {
        if (kind == null && id == null) return Mono.empty();
        if (kind == null || id == null) {
            return Mono.error(new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "liaisonKind and liaisonUserId must be supplied together"));
        }
        return switch (kind) {
            case "MEMBER" -> memberRepository.existsById(id)
                .flatMap(exists -> Boolean.TRUE.equals(exists)
                    ? Mono.<Void>empty()
                    : Mono.error(new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "No member found for liaisonUserId " + id)));
            case "STAFF" -> staffUserRepository.existsById(id)
                .flatMap(exists -> Boolean.TRUE.equals(exists)
                    ? Mono.<Void>empty()
                    : Mono.error(new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "No staff user found for liaisonUserId " + id)));
            case "LIAISON" -> groupLiaisonRepository.existsById(id)
                .flatMap(exists -> Boolean.TRUE.equals(exists)
                    ? Mono.<Void>empty()
                    : Mono.error(new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "No group liaison found for liaisonUserId " + id)));
            default -> Mono.error(new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Unknown liaisonKind '" + kind + "' (must be MEMBER, STAFF, or LIAISON)"));
        };
    }

    /**
     * Add the {@code group_liaison} realm role to whichever Keycloak user
     * backs this liaison so they can authenticate against the group portal.
     * LIAISON kind users already get the role at create-time; MEMBER and
     * STAFF users keep their existing roles and gain this one. Realm
     * differs per kind: tenant realm for MEMBER and LIAISON; the platform
     * realm for STAFF (where tenant staff actually live).
     *
     * <p>Errors from Keycloak are logged-and-swallowed (matching the
     * existing pattern in {@link KeycloakSyncService}) so a Keycloak hiccup
     * doesn't block the operational save. The DB row holds the source of
     * truth — the role mirror can be reconciled later.
     *
     * <p>TODO: on unassign (or change of kind) we should remove the role
     * if the user is no longer the liaison of any other group. That needs
     * a "still liaison of any group?" query and is deferred to a follow-up.
     */
    private Mono<Void> grantLiaisonRole(String kind, UUID id) {
        // No-op on no-change (null) or explicit clear: the role-removal
        // path is the TODO above; this helper only handles assignment.
        if (kind == null || "CLEAR".equals(kind) || id == null) return Mono.empty();
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            String tenantRealm = "tenant-" + (tenantId != null ? tenantId : "unknown");
            return switch (kind) {
                case "MEMBER" -> memberRepository.findById(id)
                    .flatMap(m -> assignIfPossible(tenantRealm, m.getKeycloakUserId()));
                case "STAFF" -> staffUserRepository.findById(id)
                    .flatMap(s -> assignIfPossible(PLATFORM_REALM, s.getKeycloakUserId()));
                case "LIAISON" -> groupLiaisonRepository.findById(id)
                    .flatMap(l -> assignIfPossible(tenantRealm, l.getKeycloakUserId()));
                default -> Mono.empty();
            };
        });
    }

    private Mono<Void> assignIfPossible(String realm, String keycloakUserId) {
        if (keycloakUserId == null || keycloakUserId.isBlank()) {
            log.warn("Skipping group_liaison role assignment — Keycloak user id is blank for realm {}", realm);
            return Mono.empty();
        }
        // Ensure the realm role exists before assigning. For tenant realms
        // it's pre-seeded by KeycloakRealmService.createRealm; for the
        // platform realm (where staff users live) this is the bootstrap.
        return keycloakSyncService.ensureRealmRole(realm, GROUP_LIAISON_ROLE,
                "Manages their group's invoices via the group portal")
            .then(keycloakSyncService.assignRealmRoles(realm, keycloakUserId,
                java.util.List.of(GROUP_LIAISON_ROLE)));
    }

    @Transactional
    public Mono<Group> suspend(UUID id, String actorId, String actorEmail) {
        return suspend(id, null, null, actorId, actorEmail);
    }

    @Transactional
    public Mono<Group> suspend(UUID id, java.time.LocalDate effectiveDate, String reason,
                                String actorId, String actorEmail) {
        return applyOrScheduleGroup(id, "suspended", effectiveDate, reason, actorId, actorEmail);
    }

    @Transactional
    public Mono<Group> activate(UUID id, java.time.LocalDate effectiveDate, String reason,
                                 String actorId, String actorEmail) {
        return applyOrScheduleGroup(id, "active", effectiveDate, reason, actorId, actorEmail);
    }

    @Transactional
    public Mono<Group> terminate(UUID id, java.time.LocalDate effectiveDate, String reason,
                                  String actorId, String actorEmail) {
        return applyOrScheduleGroup(id, "terminated", effectiveDate, reason, actorId, actorEmail);
    }

    @Transactional
    public Mono<Group> deactivate(UUID id, java.time.LocalDate effectiveDate, String reason,
                                   String actorId, String actorEmail) {
        return applyOrScheduleGroup(id, "deactivated", effectiveDate, reason, actorId, actorEmail);
    }

    /**
     * Group-lifecycle router. Same shape as
     * {@code MemberService.applyOrSchedule} — future dates land in the
     * scheduled trio, immediate transitions flip status + audit + cascade
     * to members. Members in {@code active} or {@code suspended} get
     * the same target status (with the same reason); already-terminated
     * or already-deactivated rows are left alone per the plan.
     */
    private Mono<Group> applyOrScheduleGroup(UUID id, String targetStatus,
                                              java.time.LocalDate effectiveDate,
                                              String reason,
                                              String actorId, String actorEmail) {
        java.time.LocalDate today = java.time.LocalDate.now();
        // Snap to the cycle boundary that fits the target status
        // (feedback_effective_date_snap): terminate/deactivate → end of
        // month; activate/suspend/reactivate → 1st. Belt-and-braces with
        // @EndOfMonth/@FirstOfMonth on the request DTO.
        java.time.LocalDate snappedEffective = snapForAction(targetStatus, effectiveDate);
        boolean isFuture = snappedEffective != null && snappedEffective.isAfter(today);
        return groupRepository.findById(id)
            .switchIfEmpty(Mono.error(new GroupNotFoundException(id)))
            .flatMap(existing -> {
                String previousStatus = existing.getStatus();
                if (isFuture) {
                    existing.setScheduledStatus(targetStatus);
                    existing.setScheduledStatusEffectiveFrom(snappedEffective);
                    existing.setScheduledStatusReason(reason);
                } else {
                    existing.setStatus(targetStatus);
                    existing.setScheduledStatus(null);
                    existing.setScheduledStatusEffectiveFrom(null);
                    existing.setScheduledStatusReason(null);
                    // V043 — persist the reason for non-active transitions
                    // so the arrears executor can find "arrears-suspended
                    // groups" without walking the audit trail. Clear on
                    // return to active.
                    if ("active".equals(targetStatus)) {
                        existing.setSuspendReason(null);
                    } else if (reason != null && !reason.isBlank()) {
                        existing.setSuspendReason(reason);
                    }
                }
                existing.setUpdatedAt(Instant.now());
                existing.setUpdatedBy(safeParseUuid(actorId));
                return groupRepository.save(existing)
                    .flatMap(saved -> Mono.deferContextual(ctx -> {
                        String tenantId = TenantContext.get(ctx);
                        Map<String, Object> oldVal = Map.of("status", previousStatus != null ? previousStatus : "?");
                        Map<String, Object> newVal = isFuture
                            ? Map.of("scheduledStatus", targetStatus,
                                     "scheduledEffective", snappedEffective.toString())
                            : Map.of("status", targetStatus);
                        var event = AuditEvent.create(
                            tenantId != null ? tenantId : "unknown", "Group", saved.getId().toString(),
                            saved.getName(),
                            "UPDATE", actorId, actorEmail,
                            oldVal, newVal,
                            new String[]{"status"},
                            UUID.randomUUID().toString()
                        );
                        Mono<Void> auditThenCascade = auditPublisher.publish(event);
                        // Publish GROUP_STATUS_CHANGED for immediate flips so
                        // notification-service can email the liaison. Skipped
                        // for future-dated schedules — that event fires when
                        // the daily roll actually applies it.
                        if (!isFuture) {
                            auditThenCascade = auditThenCascade
                                .then(eventPublisher.publishGroupLifecycle(
                                        tenantId, saved.getId().toString(), targetStatus, reason));
                        }
                        if (!isFuture && shouldCascadeToMembers(targetStatus)) {
                            auditThenCascade = auditThenCascade.then(cascadeToMembers(
                                    saved.getId(), targetStatus, reason, actorId, actorEmail));
                        }
                        return auditThenCascade.thenReturn(saved);
                    }));
            });
    }

    /** Only deactivated / terminated cascade. Suspended and activated
     *  group changes leave individual member statuses alone. */
    private static boolean shouldCascadeToMembers(String targetStatus) {
        return "deactivated".equals(targetStatus) || "terminated".equals(targetStatus);
    }

    /**
     * Cascade the group-level transition to every member currently in
     * {@code active} or {@code suspended}. Skips already-terminated /
     * already-deactivated rows per the plan (preserves audit history).
     * Errors on individual members are logged and swallowed so one
     * bad row doesn't leave the group half-cascaded.
     */
    private Mono<Void> cascadeToMembers(UUID groupId, String targetStatus, String reason,
                                         String actorId, String actorEmail) {
        return memberRepository.findByGroupId(groupId)
            .filter(m -> "active".equals(m.getStatus()) || "suspended".equals(m.getStatus()))
            .flatMap(m -> {
                Mono<?> op = switch (targetStatus) {
                    case "deactivated" -> memberService.deactivate(m.getId(), null, reason, actorId, actorEmail);
                    case "terminated"  -> memberService.terminate(m.getId(), null, reason, actorId, actorEmail);
                    default -> Mono.empty();
                };
                return op.onErrorResume(err -> {
                    log.warn("Cascade to member {} failed: {}", m.getId(), err.getMessage());
                    return Mono.empty();
                });
            })
            .then();
    }

    private static UUID safeParseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); }
        catch (IllegalArgumentException e) { return null; }
    }

    /**
     * Snap a lifecycle effective date to the correct cycle boundary for the
     * given target status. Terminate/deactivate → end-of-month;
     * activate/suspend → 1st. Mirrors {@code MemberService.snapForAction}
     * so both services agree on the semantics.
     */
    private static java.time.LocalDate snapForAction(String targetStatus, java.time.LocalDate effectiveDate) {
        if (effectiveDate == null) return null;
        return switch (targetStatus) {
            case "terminated", "deactivated" ->
                com.medfund.shared.validation.DateSnaps.toEndOfMonth(effectiveDate);
            case "active", "suspended" ->
                com.medfund.shared.validation.DateSnaps.toFirstOfMonth(effectiveDate);
            default -> effectiveDate;
        };
    }
}

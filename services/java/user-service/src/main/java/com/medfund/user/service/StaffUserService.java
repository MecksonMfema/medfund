package com.medfund.user.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import com.medfund.user.dto.CreateStaffUserRequest;
import com.medfund.user.dto.CursorPage;
import com.medfund.user.dto.StaffUserResponse;
import com.medfund.user.dto.UpdateStaffUserRequest;
import com.medfund.user.entity.StaffUser;
import com.medfund.user.entity.UserRole;
import com.medfund.user.repository.StaffUserRepository;
import com.medfund.user.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class StaffUserService {

    private static final String   PLATFORM_REALM  = "medfund-platform";
    private static final String   PLATFORM_TENANT = "platform";
    private static final Duration CACHE_TTL       = Duration.ofMinutes(2);
    /** How long an invitation email stays valid. Mirrored to the Keycloak action-link lifespan. */
    public  static final Duration INVITE_TTL      = Duration.ofDays(7);
    private static final TypeReference<List<StaffUser>> LIST_TYPE = new TypeReference<>() {};

    private final StaffUserRepository         repository;
    private final KeycloakSyncService         keycloakSyncService;
    private final AuditPublisher              auditPublisher;
    private final ReactiveStringRedisTemplate redis;
    private final ObjectMapper                objectMapper;
    private final R2dbcEntityTemplate         r2dbcTemplate;
    private final UserRoleRepository          userRoleRepository;
    private final UserEventPublisher          userEventPublisher;

    // ── Reads (cached) ────────────────────────────────────────────────────────

    /** Platform super_admin users only (tenant_id IS NULL). */
    public Flux<StaffUser> findPlatformStaff() {
        return cached("staff-users:platform", repository.findPlatformStaff());
    }

    /** All staff belonging to a specific tenant. */
    public Flux<StaffUser> findByTenantId(UUID tenantId) {
        return cached("staff-users:tenant:" + tenantId, repository.findByTenantId(tenantId));
    }

    public Flux<StaffUser> findAll() {
        return cached("staff-users:all", repository.findAllOrderByCreatedAtDesc());
    }

    public Mono<StaffUser> findById(UUID id) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Staff user not found: " + id)));
    }

    /**
     * Lookup by the Keycloak {@code sub} (the JWT subject we record on audit
     * events as {@code actorId}). Used by the audit page to resolve the actor
     * column to a real name when the event predates {@code actorEmail} capture.
     */
    public Mono<StaffUser> findByKeycloakUserId(String keycloakUserId) {
        return repository.findByKeycloakUserId(keycloakUserId)
                .switchIfEmpty(Mono.error(new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND,
                        "Staff user not found for keycloakUserId: " + keycloakUserId)));
    }

    public Flux<StaffUser> findByStatus(String status, UUID tenantId) {
        if (tenantId != null) {
            return cached("staff-users:tenant:" + tenantId + ":status:" + status,
                    repository.findByTenantIdAndStatus(tenantId, status));
        }
        return cached("staff-users:platform:status:" + status,
                repository.findPlatformStaffByStatus(status));
    }

    public Flux<StaffUser> findByRole(String role) {
        return cached("staff-users:role:" + role, repository.findByRealmRole(role));
    }

    public Flux<StaffUser> search(String query, UUID tenantId) {
        if (tenantId != null) {
            return cached("staff-users:tenant:" + tenantId + ":search:" + query.toLowerCase(),
                    repository.searchByTenant(tenantId, query));
        }
        return cached("staff-users:platform:search:" + query.toLowerCase(),
                repository.searchPlatformStaff(query));
    }

    /**
     * Cursor-based paginated list of staff users, scoped to a tenant (or platform if null).
     * Supports optional full-text search on name/email.
     */
    public Mono<CursorPage<StaffUserResponse>> findPage(UUID tenantId, String q, String cursor, int limit) {
        Instant cursorTs = null;
        UUID cursorId = null;
        if (cursor != null && !cursor.isBlank()) {
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split(":", 2);
            cursorTs = Instant.ofEpochMilli(Long.parseLong(parts[0]));
            cursorId = UUID.fromString(parts[1]);
        }

        boolean hasQ = q != null && !q.isBlank();
        int fetch = limit + 1;
        boolean isTenant = tenantId != null;

        Flux<StaffUser> source;
        if (hasQ) {
            source = isTenant
                    ? (cursorTs == null
                        ? repository.searchTenantFirstPage(tenantId, q, fetch)
                        : repository.searchTenantNextPage(tenantId, q, cursorTs, cursorId, fetch))
                    : (cursorTs == null
                        ? repository.searchPlatformFirstPage(q, fetch)
                        : repository.searchPlatformNextPage(q, cursorTs, cursorId, fetch));
        } else {
            source = isTenant
                    ? (cursorTs == null
                        ? repository.findTenantFirstPage(tenantId, fetch)
                        : repository.findTenantNextPage(tenantId, cursorTs, cursorId, fetch))
                    : (cursorTs == null
                        ? repository.findPlatformFirstPage(fetch)
                        : repository.findPlatformNextPage(cursorTs, cursorId, fetch));
        }

        return source.collectList().map(rows -> {
            boolean hasMore = rows.size() > limit;
            List<StaffUser> content = hasMore ? rows.subList(0, limit) : rows;
            String nextCursor = null;
            if (hasMore && !content.isEmpty()) {
                StaffUser last = content.get(content.size() - 1);
                String raw = last.getCreatedAt().toEpochMilli() + ":" + last.getId();
                nextCursor = Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
            }
            return new CursorPage<>(content.stream().map(StaffUserResponse::from).toList(), nextCursor, hasMore, limit);
        });
    }

    public Mono<StaffUser> resendInvite(UUID id) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new java.util.NoSuchElementException("Staff user not found: " + id)))
                .flatMap(user -> {
                    if (user.getKeycloakUserId() == null) {
                        return Mono.error(new IllegalStateException(
                                "User has no Keycloak account — create the user first"));
                    }
                    if ("active".equalsIgnoreCase(user.getStatus())) {
                        return Mono.error(new IllegalStateException(
                                "User has already accepted their invitation"));
                    }
                    return keycloakSyncService.sendInviteEmail(PLATFORM_REALM, user.getKeycloakUserId())
                            .then(Mono.defer(() -> {
                                user.setInvitedAt(Instant.now());
                                if (!"invited".equalsIgnoreCase(user.getStatus())) {
                                    user.setStatus("invited");
                                }
                                return repository.save(user);
                            }))
                            .flatMap(saved -> evict().thenReturn(saved));
                });
    }

    /**
     * Marks a user as having accepted their invitation. Called by the
     * security-event consumer when Keycloak emits a successful UPDATE_PASSWORD
     * for the user's keycloak_user_id. No-op if no matching staff user exists
     * or the user is already active.
     */
    public Mono<Void> markInviteAccepted(String keycloakUserId) {
        if (keycloakUserId == null || keycloakUserId.isBlank()) return Mono.empty();
        return repository.findByKeycloakUserId(keycloakUserId)
                .filter(u -> "invited".equalsIgnoreCase(u.getStatus()))
                .flatMap(u -> {
                    Map<String, Object> before = toMap(u);
                    u.setStatus("active");
                    return repository.save(u)
                            .flatMap(saved -> audit(saved, "INVITE_ACCEPTED", null, null,
                                    before, toMap(saved), new String[]{"status"}))
                            .flatMap(saved -> evict().thenReturn(saved));
                })
                .then();
    }

    // ── Writes (each evicts all staff-user caches) ────────────────────────────

    public Mono<StaffUser> create(CreateStaffUserRequest request, String actorId, String actorEmail) {
        return repository.existsByEmail(request.email())
                .flatMap(exists -> {
                    if (exists) return Mono.error(new IllegalStateException(
                            "A staff user with email " + request.email() + " already exists"));

                    StaffUser user = new StaffUser();
                    user.setFirstName(request.firstName());
                    user.setLastName(request.lastName());
                    user.setEmail(request.email());
                    user.setPhone(request.phone());
                    user.setJobTitle(request.jobTitle());
                    user.setDepartment(request.department());
                    user.setRealmRole(request.realmRole());
                    user.setTenantId(request.tenantId()); // null for super_admin
                    user.setStatus("invited");
                    user.setInvitedAt(Instant.now());
                    if (actorId != null) {
                        UUID actorUuid = UUID.fromString(actorId);
                        user.setCreatedBy(actorUuid);
                        user.setUpdatedBy(actorUuid);
                    }

                    return r2dbcTemplate.insert(user);
                })
                .flatMap(saved -> keycloakSyncService
                        .createUser(PLATFORM_REALM, saved.getEmail(),
                                saved.getFirstName(), saved.getLastName(),
                                List.of(saved.getRealmRole()))
                        .flatMap(keycloakId -> {
                            saved.setKeycloakUserId(keycloakId);
                            return repository.save(saved)
                                    .flatMap(s -> keycloakSyncService
                                            .sendInviteEmail(PLATFORM_REALM, keycloakId)
                                            .thenReturn(s));
                        })
                        .defaultIfEmpty(saved))
                // After the user exists in both DB + Keycloak, write tenant
                // user_roles rows. Skips for super_admin (tenantId == null).
                .flatMap(saved -> syncTenantRoles(saved.getId(), saved.getTenantId(),
                                request.roleIds(), actorId)
                        .thenReturn(saved))
                .flatMap(saved -> audit(saved, "CREATE", actorId, actorEmail,
                        null, toMap(saved), null))
                .flatMap(saved -> evict().thenReturn(saved));
    }

    public Mono<StaffUser> update(UUID id, UpdateStaffUserRequest request, String actorId, String actorEmail) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new java.util.NoSuchElementException("Staff user not found: " + id)))
                .flatMap(user -> {
                    Map<String, Object> before = toMap(user); // snapshot before mutation
                    String oldRole = user.getRealmRole();

                    if (request.firstName()  != null) user.setFirstName(request.firstName());
                    if (request.lastName()   != null) user.setLastName(request.lastName());
                    if (request.phone()      != null) user.setPhone(request.phone());
                    if (request.jobTitle()   != null) user.setJobTitle(request.jobTitle());
                    if (request.department() != null) user.setDepartment(request.department());
                    if (request.realmRole()  != null) user.setRealmRole(request.realmRole());
                    user.setUpdatedAt(Instant.now());
                    if (actorId != null) user.setUpdatedBy(UUID.fromString(actorId));

                    return repository.save(user)
                            .flatMap(saved -> {
                                Mono<StaffUser> afterSync;
                                if (request.realmRole() != null
                                        && !request.realmRole().equals(oldRole)
                                        && saved.getKeycloakUserId() != null) {
                                    afterSync = keycloakSyncService
                                            .removeRealmRole(PLATFORM_REALM, saved.getKeycloakUserId(), oldRole)
                                            .then(keycloakSyncService.assignRealmRoles(
                                                    PLATFORM_REALM, saved.getKeycloakUserId(),
                                                    List.of(saved.getRealmRole())))
                                            .thenReturn(saved);
                                } else {
                                    afterSync = Mono.just(saved);
                                }
                                Map<String, Object> after = toMap(saved);
                                Mono<StaffUser> withRoles = afterSync
                                        // When the request supplies roleIds, replace the
                                        // user's tenant role assignments. Null = leave alone.
                                        .flatMap(s -> syncTenantRoles(s.getId(), s.getTenantId(),
                                                request.roleIds(), actorId).thenReturn(s));
                                return withRoles.flatMap(s -> audit(s, "UPDATE", actorId, actorEmail,
                                        before, after, computeChangedFields(before, after)));
                            });
                })
                .flatMap(saved -> evict().thenReturn(saved));
    }

    public Mono<StaffUser> suspend(UUID id, String actorId, String actorEmail) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new java.util.NoSuchElementException("Staff user not found: " + id)))
                .flatMap(user -> {
                    Map<String, Object> before = toMap(user);
                    user.setStatus("suspended");
                    user.setUpdatedAt(Instant.now());
                    if (actorId != null) user.setUpdatedBy(UUID.fromString(actorId));
                    return repository.save(user)
                            .flatMap(saved -> saved.getKeycloakUserId() != null
                                    ? keycloakSyncService.disableUser(PLATFORM_REALM, saved.getKeycloakUserId()).thenReturn(saved)
                                    : Mono.just(saved))
                            .flatMap(saved -> audit(saved, "UPDATE", actorId, actorEmail,
                                    before, toMap(saved), new String[]{"status"}));
                })
                .flatMap(saved -> evict().thenReturn(saved));
    }

    public Mono<StaffUser> activate(UUID id, String actorId, String actorEmail) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new java.util.NoSuchElementException("Staff user not found: " + id)))
                .flatMap(user -> {
                    Map<String, Object> before = toMap(user);
                    user.setStatus("active");
                    user.setUpdatedAt(Instant.now());
                    if (actorId != null) user.setUpdatedBy(UUID.fromString(actorId));
                    return repository.save(user)
                            .flatMap(saved -> saved.getKeycloakUserId() != null
                                    ? keycloakSyncService.enableUser(PLATFORM_REALM, saved.getKeycloakUserId()).thenReturn(saved)
                                    : Mono.just(saved))
                            .flatMap(saved -> audit(saved, "UPDATE", actorId, actorEmail,
                                    before, toMap(saved), new String[]{"status"}));
                })
                .flatMap(saved -> evict().thenReturn(saved));
    }

    public Mono<Void> delete(UUID id, String actorId, String actorEmail) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new java.util.NoSuchElementException("Staff user not found: " + id)))
                .flatMap(user -> {
                    Map<String, Object> before = toMap(user);
                    String entityName = displayName(user);
                    Mono<Void> keycloakCleanup = user.getKeycloakUserId() != null
                            ? keycloakSyncService.disableUser(PLATFORM_REALM, user.getKeycloakUserId())
                            : Mono.empty();
                    String tenantContext = user.getTenantId() != null
                            ? user.getTenantId().toString() : PLATFORM_TENANT;
                    return keycloakCleanup
                            .then(repository.deleteById(id))
                            .then(Mono.defer(() -> {
                                var event = AuditEvent.create(
                                        tenantContext, "USER", id.toString(), entityName,
                                        "DELETE", actorId, actorEmail,
                                        before, null, null,
                                        UUID.randomUUID().toString());
                                return auditPublisher.publish(event);
                            }))
                            .then(evict());
                });
    }

    // ── Cache helpers ─────────────────────────────────────────────────────────

    private Flux<StaffUser> cached(String key, Flux<StaffUser> source) {
        return redis.opsForValue().get(key)
                .flatMapMany(json -> {
                    try {
                        return Flux.fromIterable(objectMapper.readValue(json, LIST_TYPE));
                    } catch (JsonProcessingException e) {
                        return Flux.<StaffUser>empty();
                    }
                })
                .switchIfEmpty(source.collectList()
                        .flatMapMany(list -> {
                            try {
                                String json = objectMapper.writeValueAsString(list);
                                return redis.opsForValue().set(key, json, CACHE_TTL)
                                        .thenMany(Flux.fromIterable(list));
                            } catch (JsonProcessingException e) {
                                return Flux.fromIterable(list);
                            }
                        }));
    }

    private Mono<Void> evict() {
        return redis.keys("staff-users:*")
                .collectList()
                .flatMap(keys -> keys.isEmpty() ? Mono.just(0L) : redis.delete(keys.toArray(String[]::new)))
                .then();
    }

    // ── Tenant-role assignment helpers ────────────────────────────────────────

    /**
     * Replace the user's tenant {@code user_roles} rows with exactly
     * {@code roleIds}, scoping all DB writes to {@code tenantId}'s schema via
     * the reactive context. Idempotent — diffing happens by skipping inserts
     * that already exist (UNIQUE(user_id, role_id) makes them no-ops). When
     * {@code roleIds} is null this is a no-op; when empty, every existing
     * assignment is revoked.
     *
     * <p>Publishes one targeted invalidation event so all services drop the
     * affected user's cached permission set within ~1 s of the write.
     */
    private Mono<Void> syncTenantRoles(UUID userId, UUID tenantId, List<UUID> roleIds, String actorId) {
        if (tenantId == null || roleIds == null) return Mono.empty();
        return userRoleRepository.findByUserId(userId)
                .map(UserRole::getRoleId)
                .collectList()
                .flatMap(existing -> {
                    List<UUID> toAdd = roleIds.stream().filter(id -> !existing.contains(id)).toList();
                    List<UUID> toRemove = existing.stream().filter(id -> !roleIds.contains(id)).toList();

                    Mono<Void> adds = Flux.fromIterable(toAdd)
                            .flatMap(roleId -> {
                                var ur = new UserRole();
                                ur.setUserId(userId);
                                ur.setRoleId(roleId);
                                ur.setAssignedAt(Instant.now());
                                if (actorId != null) {
                                    try { ur.setAssignedBy(UUID.fromString(actorId)); } catch (Exception ignored) {}
                                }
                                return r2dbcTemplate.insert(ur);
                            })
                            .then();

                    Mono<Void> removes = Flux.fromIterable(toRemove)
                            .flatMap(roleId -> userRoleRepository.deleteByUserIdAndRoleId(userId, roleId))
                            .then();

                    Mono<Void> invalidate = (toAdd.isEmpty() && toRemove.isEmpty())
                            ? Mono.empty()
                            : userEventPublisher.publishPermissionsInvalidated(
                                    tenantId.toString(), userId.toString());

                    return adds.then(removes).then(invalidate);
                })
                .contextWrite(ctx -> TenantContext.put(ctx, tenantId.toString()));
    }

    // ── Audit helpers ─────────────────────────────────────────────────────────

    private Mono<StaffUser> audit(StaffUser user, String action, String actorId, String actorEmail,
                                   Map<String, Object> before, Map<String, Object> after,
                                   String[] changedFields) {
        // Tag the event with the staff user's tenant when assigned; fall back to
        // the platform sentinel so super-admin events stay in the platform audit log.
        String tenantContext = user.getTenantId() != null
                ? user.getTenantId().toString()
                : PLATFORM_TENANT;
        var event = AuditEvent.create(
                tenantContext, "USER", user.getId().toString(), displayName(user),
                action, actorId, actorEmail, before, after, changedFields,
                UUID.randomUUID().toString());
        return auditPublisher.publish(event).thenReturn(user);
    }

    /** All fields that should appear in the audit diff. */
    private Map<String, Object> toMap(StaffUser u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("firstName",  u.getFirstName());
        m.put("lastName",   u.getLastName());
        m.put("email",      u.getEmail());
        m.put("phone",      u.getPhone());
        m.put("jobTitle",   u.getJobTitle());
        m.put("department", u.getDepartment());
        m.put("realmRole",  u.getRealmRole());
        m.put("tenantId",   u.getTenantId());
        m.put("status",     u.getStatus());
        return m;
    }

    private String[] computeChangedFields(Map<String, Object> before, Map<String, Object> after) {
        List<String> changed = new ArrayList<>();
        for (String key : after.keySet()) {
            if (!Objects.equals(before.get(key), after.get(key))) {
                changed.add(key);
            }
        }
        return changed.toArray(String[]::new);
    }

    /** Human-readable name for audit display: "First Last" or email as fallback. */
    private String displayName(StaffUser u) {
        if (u.getFirstName() != null && u.getLastName() != null) {
            return u.getFirstName() + " " + u.getLastName();
        }
        return u.getEmail() != null ? u.getEmail() : u.getId().toString();
    }
}

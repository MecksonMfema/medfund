package com.medfund.user.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.user.dto.CreateStaffUserRequest;
import com.medfund.user.dto.UpdateStaffUserRequest;
import com.medfund.user.entity.StaffUser;
import com.medfund.user.repository.StaffUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
    private static final TypeReference<List<StaffUser>> LIST_TYPE = new TypeReference<>() {};

    private final StaffUserRepository         repository;
    private final KeycloakSyncService         keycloakSyncService;
    private final AuditPublisher              auditPublisher;
    private final ReactiveStringRedisTemplate redis;
    private final ObjectMapper                objectMapper;
    private final R2dbcEntityTemplate         r2dbcTemplate;

    // ── Reads (cached) ────────────────────────────────────────────────────────

    public Flux<StaffUser> findAll() {
        return cached("staff-users:all", repository.findAllOrderByCreatedAtDesc());
    }

    public Mono<StaffUser> findById(UUID id) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new RuntimeException("Staff user not found: " + id)));
    }

    public Flux<StaffUser> findByStatus(String status) {
        return cached("staff-users:status:" + status, repository.findByStatus(status));
    }

    public Flux<StaffUser> findByRole(String role) {
        return cached("staff-users:role:" + role, repository.findByRealmRole(role));
    }

    public Flux<StaffUser> search(String query) {
        return cached("staff-users:search:" + query.toLowerCase(), repository.search(query));
    }

    public Mono<StaffUser> resendInvite(UUID id) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new RuntimeException("Staff user not found: " + id)))
                .flatMap(user -> {
                    if (user.getKeycloakUserId() == null) {
                        return Mono.error(new RuntimeException(
                                "User has no Keycloak account — create the user first"));
                    }
                    return keycloakSyncService.sendInviteEmail(PLATFORM_REALM, user.getKeycloakUserId())
                            .thenReturn(user);
                });
    }

    // ── Writes (each evicts all staff-user caches) ────────────────────────────

    public Mono<StaffUser> create(CreateStaffUserRequest request, String actorId, String actorEmail) {
        return repository.existsByEmail(request.email())
                .flatMap(exists -> {
                    if (exists) return Mono.error(new RuntimeException(
                            "A staff user with email " + request.email() + " already exists"));

                    StaffUser user = new StaffUser();
                    user.setFirstName(request.firstName());
                    user.setLastName(request.lastName());
                    user.setEmail(request.email());
                    user.setPhone(request.phone());
                    user.setJobTitle(request.jobTitle());
                    user.setDepartment(request.department());
                    user.setRealmRole(request.realmRole());
                    user.setStatus("active");
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
                .flatMap(saved -> audit(saved, "CREATE", actorId, actorEmail,
                        null, toMap(saved), null))
                .flatMap(saved -> evict().thenReturn(saved));
    }

    public Mono<StaffUser> update(UUID id, UpdateStaffUserRequest request, String actorId, String actorEmail) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new RuntimeException("Staff user not found: " + id)))
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
                                return afterSync.flatMap(s -> audit(s, "UPDATE", actorId, actorEmail,
                                        before, after, computeChangedFields(before, after)));
                            });
                })
                .flatMap(saved -> evict().thenReturn(saved));
    }

    public Mono<StaffUser> suspend(UUID id, String actorId, String actorEmail) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new RuntimeException("Staff user not found: " + id)))
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
                .switchIfEmpty(Mono.error(new RuntimeException("Staff user not found: " + id)))
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
                .switchIfEmpty(Mono.error(new RuntimeException("Staff user not found: " + id)))
                .flatMap(user -> {
                    Map<String, Object> before = toMap(user);
                    String entityName = displayName(user);
                    Mono<Void> keycloakCleanup = user.getKeycloakUserId() != null
                            ? keycloakSyncService.disableUser(PLATFORM_REALM, user.getKeycloakUserId())
                            : Mono.empty();
                    return keycloakCleanup
                            .then(repository.deleteById(id))
                            .then(Mono.defer(() -> {
                                var event = AuditEvent.create(
                                        PLATFORM_TENANT, "USER", id.toString(), entityName,
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

    // ── Audit helpers ─────────────────────────────────────────────────────────

    private Mono<StaffUser> audit(StaffUser user, String action, String actorId, String actorEmail,
                                   Map<String, Object> before, Map<String, Object> after,
                                   String[] changedFields) {
        var event = AuditEvent.create(
                PLATFORM_TENANT, "USER", user.getId().toString(), displayName(user),
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

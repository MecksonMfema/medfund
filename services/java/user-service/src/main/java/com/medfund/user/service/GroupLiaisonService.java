package com.medfund.user.service;

import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import com.medfund.user.dto.CreateGroupLiaisonRequest;
import com.medfund.user.entity.GroupLiaison;
import com.medfund.user.exception.GroupLiaisonNotFoundException;
import com.medfund.user.repository.GroupLiaisonRepository;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Manages "pure" group-liaison records (people who aren't members or staff,
 * but log in to the group portal to handle a group's invoices). Mirrors the
 * StaffUserService create-then-Keycloak-provision-then-invite flow, scoped
 * to the tenant realm and the canonical {@code group_liaison} realm role.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroupLiaisonService {

    public static final String REALM_ROLE = "group_liaison";

    private final GroupLiaisonRepository repository;
    private final R2dbcEntityTemplate r2dbcTemplate;
    private final AuditPublisher auditPublisher;
    private final KeycloakSyncService keycloakSyncService;

    public Mono<GroupLiaison> findById(UUID id) {
        return repository.findById(id)
            .switchIfEmpty(Mono.error(new GroupLiaisonNotFoundException(id)));
    }

    public Flux<GroupLiaison> search(String q, int limit) {
        if (q == null || q.isBlank()) return Flux.empty();
        return repository.search(q.trim(), Math.min(Math.max(limit, 1), 50));
    }

    public Flux<GroupLiaison> findAll() {
        return repository.findAllOrderByCreatedAtDesc();
    }

    @Transactional
    public Mono<GroupLiaison> create(CreateGroupLiaisonRequest request, String actorId, String actorEmail) {
        return repository.existsByEmailIgnoreCase(request.email())
            .flatMap(exists -> {
                if (Boolean.TRUE.equals(exists)) {
                    return Mono.<GroupLiaison>error(new ResponseStatusException(
                        HttpStatus.CONFLICT, "A liaison with email " + request.email() + " already exists"));
                }
                var liaison = new GroupLiaison();
                liaison.setFirstName(request.firstName());
                liaison.setLastName(request.lastName());
                liaison.setEmail(request.email());
                liaison.setPhone(request.phone());
                liaison.setAddress(request.address());
                liaison.setStatus("invited");
                liaison.setCreatedAt(Instant.now());
                liaison.setUpdatedAt(Instant.now());
                if (actorId != null) {
                    UUID actor = UUID.fromString(actorId);
                    liaison.setCreatedBy(actor);
                    liaison.setUpdatedBy(actor);
                }
                return r2dbcTemplate.insert(liaison);
            })
            .flatMap(saved -> Mono.deferContextual(ctx -> {
                String tenantId = TenantContext.get(ctx);
                String realm = "tenant-" + (tenantId != null ? tenantId : "unknown");
                return keycloakSyncService.createUser(
                        realm, saved.getEmail(),
                        saved.getFirstName(), saved.getLastName(),
                        List.of(REALM_ROLE))
                    .flatMap(keycloakId -> {
                        saved.setKeycloakUserId(keycloakId);
                        return repository.save(saved)
                            .flatMap(s -> keycloakSyncService.sendInviteEmail(realm, keycloakId)
                                .thenReturn(s));
                    })
                    .defaultIfEmpty(saved)
                    .flatMap(s -> publishCreateAudit(s, tenantId, actorId, actorEmail).thenReturn(s));
            }));
    }

    private Mono<Void> publishCreateAudit(GroupLiaison saved, String tenantId, String actorId, String actorEmail) {
        var event = AuditEvent.create(
            tenantId != null ? tenantId : "unknown", "GroupLiaison", saved.getId().toString(),
            saved.getFirstName() + " " + saved.getLastName(),
            "CREATE", actorId, actorEmail, null,
            Map.of(
                "firstName", saved.getFirstName(),
                "lastName",  saved.getLastName(),
                "email",     saved.getEmail()
            ),
            new String[]{"firstName", "lastName", "email"},
            UUID.randomUUID().toString()
        );
        return auditPublisher.publish(event);
    }
}

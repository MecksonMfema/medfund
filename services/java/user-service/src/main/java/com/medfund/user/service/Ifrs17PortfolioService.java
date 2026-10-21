package com.medfund.user.service;

import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import com.medfund.user.dto.CreateIfrs17PortfolioRequest;
import com.medfund.user.dto.UpdateIfrs17PortfolioRequest;
import com.medfund.user.entity.Ifrs17Portfolio;
import com.medfund.user.exception.Ifrs17PortfolioNotFoundException;
import com.medfund.user.repository.Ifrs17PortfolioRepository;
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
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * CRUD for IFRS 17 portfolio dimension. Soft-deletes via {@code is_active = FALSE}
 * so historical policy references stay resolvable; hard-delete refused when policies
 * still reference the portfolio (Phase 15 will need the FK integrity).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Ifrs17PortfolioService {

    private final Ifrs17PortfolioRepository repository;
    private final R2dbcEntityTemplate r2dbcTemplate;
    private final AuditPublisher auditPublisher;

    public Mono<Ifrs17Portfolio> findById(UUID id) {
        return repository.findById(id)
            .switchIfEmpty(Mono.error(new Ifrs17PortfolioNotFoundException(id)));
    }

    public Flux<Ifrs17Portfolio> findAll(boolean includeInactive) {
        return includeInactive ? repository.findAllOrdered() : repository.findAllActive();
    }

    public Flux<Ifrs17Portfolio> search(String q, int limit) {
        if (q == null || q.isBlank()) return repository.findAllActive();
        return repository.search(q.trim(), Math.min(Math.max(limit, 1), 50));
    }

    @Transactional
    public Mono<Ifrs17Portfolio> create(CreateIfrs17PortfolioRequest request,
                                        String actorId, String actorEmail) {
        return repository.existsByNameIgnoreCase(request.name())
            .flatMap(exists -> {
                if (Boolean.TRUE.equals(exists)) {
                    return Mono.<Ifrs17Portfolio>error(new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "A portfolio with name '" + request.name() + "' already exists"));
                }
                var p = new Ifrs17Portfolio();
                p.setName(request.name());
                p.setDescription(request.description());
                p.setInsuranceLine(request.insuranceLine());
                p.setIsActive(true);
                p.setCreatedAt(Instant.now());
                p.setUpdatedAt(Instant.now());
                if (actorId != null) p.setActorId(UUID.fromString(actorId));
                p.setActorEmail(actorEmail);
                return r2dbcTemplate.insert(p);
            })
            .flatMap(saved -> publishAudit(saved, "CREATE", null, actorId, actorEmail).thenReturn(saved));
    }

    @Transactional
    public Mono<Ifrs17Portfolio> update(UUID id, UpdateIfrs17PortfolioRequest request,
                                        String actorId, String actorEmail) {
        return findById(id).flatMap(existing ->
            repository.existsByNameIgnoreCaseAndIdNot(request.name(), id)
                .flatMap(dup -> {
                    if (Boolean.TRUE.equals(dup)) {
                        return Mono.<Ifrs17Portfolio>error(new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "A portfolio with name '" + request.name() + "' already exists"));
                    }
                    Map<String, Object> oldValue = snapshot(existing);
                    existing.setName(request.name());
                    existing.setDescription(request.description());
                    existing.setInsuranceLine(request.insuranceLine());
                    existing.setUpdatedAt(Instant.now());
                    if (actorId != null) existing.setActorId(UUID.fromString(actorId));
                    existing.setActorEmail(actorEmail);
                    return repository.save(existing)
                        .flatMap(saved -> publishAudit(saved, "UPDATE", oldValue, actorId, actorEmail)
                            .thenReturn(saved));
                }));
    }

    /** Soft delete (is_active=false). Refuses if any policies still reference this portfolio. */
    @Transactional
    public Mono<Void> softDelete(UUID id, String actorId, String actorEmail) {
        return findById(id).flatMap(existing ->
            repository.countReferencingPolicies(id)
                .flatMap(count -> {
                    if (count != null && count > 0) {
                        return Mono.<Void>error(new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Cannot delete portfolio: " + count + " policies reference it"));
                    }
                    Map<String, Object> oldValue = snapshot(existing);
                    existing.setIsActive(false);
                    existing.setUpdatedAt(Instant.now());
                    if (actorId != null) existing.setActorId(UUID.fromString(actorId));
                    existing.setActorEmail(actorEmail);
                    return repository.save(existing)
                        .flatMap(saved -> publishAudit(saved, "DELETE", oldValue, actorId, actorEmail))
                        .then();
                }));
    }

    private Map<String, Object> snapshot(Ifrs17Portfolio p) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", p.getName());
        m.put("description", p.getDescription());
        m.put("insuranceLine", p.getInsuranceLine());
        m.put("isActive", p.getIsActive());
        return m;
    }

    private Mono<Void> publishAudit(Ifrs17Portfolio p, String action, Map<String, Object> oldValue,
                                    String actorId, String actorEmail) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            Map<String, Object> newValue = snapshot(p);
            String[] changed = oldValue == null
                    ? new String[]{"name", "description", "insuranceLine", "isActive"}
                    : oldValue.keySet().stream()
                        .filter(k -> !java.util.Objects.equals(oldValue.get(k), newValue.get(k)))
                        .toArray(String[]::new);
            var event = AuditEvent.create(
                    tenantId != null ? tenantId : "unknown",
                    "Ifrs17Portfolio", p.getId().toString(), p.getName(),
                    action, actorId, actorEmail,
                    oldValue, newValue, changed,
                    UUID.randomUUID().toString()
            );
            return auditPublisher.publish(event);
        });
    }
}

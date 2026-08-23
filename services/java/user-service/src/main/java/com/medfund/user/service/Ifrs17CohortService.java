package com.medfund.user.service;

import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import com.medfund.user.dto.CreateIfrs17CohortRequest;
import com.medfund.user.dto.UpdateIfrs17CohortRequest;
import com.medfund.user.entity.Ifrs17Cohort;
import com.medfund.user.exception.Ifrs17CohortNotFoundException;
import com.medfund.user.exception.Ifrs17PortfolioNotFoundException;
import com.medfund.user.repository.Ifrs17CohortRepository;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class Ifrs17CohortService {

    private final Ifrs17CohortRepository repository;
    private final Ifrs17PortfolioRepository portfolioRepository;
    private final R2dbcEntityTemplate r2dbcTemplate;
    private final AuditPublisher auditPublisher;

    public Mono<Ifrs17Cohort> findById(UUID id) {
        return repository.findById(id)
            .switchIfEmpty(Mono.error(new Ifrs17CohortNotFoundException(id)));
    }

    public Flux<Ifrs17Cohort> findAll(boolean includeInactive) {
        return includeInactive ? repository.findAllOrdered() : repository.findAllActive();
    }

    public Flux<Ifrs17Cohort> findByPortfolio(UUID portfolioId) {
        return repository.findByPortfolioId(portfolioId);
    }

    @Transactional
    public Mono<Ifrs17Cohort> create(CreateIfrs17CohortRequest request,
                                     String actorId, String actorEmail) {
        return portfolioRepository.findById(request.portfolioId())
            .switchIfEmpty(Mono.error(new Ifrs17PortfolioNotFoundException(request.portfolioId())))
            .then(Mono.defer(() -> repository.existsByCompositeKey(
                    request.portfolioId(), request.cohortYear(), request.cohortType())))
            .flatMap(exists -> {
                if (Boolean.TRUE.equals(exists)) {
                    return Mono.<Ifrs17Cohort>error(new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "A cohort with (portfolio, year, type)=("
                            + request.portfolioId() + ", " + request.cohortYear()
                            + ", " + request.cohortType() + ") already exists"));
                }
                var c = new Ifrs17Cohort();
                c.setPortfolioId(request.portfolioId());
                c.setCohortYear(request.cohortYear());
                c.setCohortType(request.cohortType());
                c.setName(request.name());
                c.setIsActive(true);
                c.setCreatedAt(Instant.now());
                c.setUpdatedAt(Instant.now());
                if (actorId != null) c.setActorId(UUID.fromString(actorId));
                c.setActorEmail(actorEmail);
                return r2dbcTemplate.insert(c);
            })
            .flatMap(saved -> publishAudit(saved, "CREATE", null, actorId, actorEmail).thenReturn(saved));
    }

    @Transactional
    public Mono<Ifrs17Cohort> update(UUID id, UpdateIfrs17CohortRequest request,
                                     String actorId, String actorEmail) {
        return findById(id).flatMap(existing ->
            portfolioRepository.findById(request.portfolioId())
                .switchIfEmpty(Mono.error(new Ifrs17PortfolioNotFoundException(request.portfolioId())))
                .then(Mono.defer(() -> repository.existsByCompositeKeyAndIdNot(
                        request.portfolioId(), request.cohortYear(), request.cohortType(), id)))
                .flatMap(dup -> {
                    if (Boolean.TRUE.equals(dup)) {
                        return Mono.<Ifrs17Cohort>error(new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "A cohort with that (portfolio, year, type) already exists"));
                    }
                    Map<String, Object> oldValue = snapshot(existing);
                    existing.setPortfolioId(request.portfolioId());
                    existing.setCohortYear(request.cohortYear());
                    existing.setCohortType(request.cohortType());
                    existing.setName(request.name());
                    existing.setUpdatedAt(Instant.now());
                    if (actorId != null) existing.setActorId(UUID.fromString(actorId));
                    existing.setActorEmail(actorEmail);
                    return repository.save(existing)
                        .flatMap(saved -> publishAudit(saved, "UPDATE", oldValue, actorId, actorEmail)
                            .thenReturn(saved));
                }));
    }

    @Transactional
    public Mono<Void> softDelete(UUID id, String actorId, String actorEmail) {
        return findById(id).flatMap(existing ->
            repository.countReferencingPolicies(id)
                .flatMap(count -> {
                    if (count != null && count > 0) {
                        return Mono.<Void>error(new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Cannot delete cohort: " + count + " policies reference it"));
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

    private Map<String, Object> snapshot(Ifrs17Cohort c) {
        Map<String, Object> m = new HashMap<>();
        m.put("portfolioId", c.getPortfolioId());
        m.put("cohortYear", c.getCohortYear());
        m.put("cohortType", c.getCohortType());
        m.put("name", c.getName());
        m.put("isActive", c.getIsActive());
        return m;
    }

    private Mono<Void> publishAudit(Ifrs17Cohort c, String action, Map<String, Object> oldValue,
                                    String actorId, String actorEmail) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            Map<String, Object> newValue = snapshot(c);
            String[] changed = oldValue == null
                    ? new String[]{"portfolioId", "cohortYear", "cohortType", "name", "isActive"}
                    : oldValue.keySet().stream()
                        .filter(k -> !java.util.Objects.equals(oldValue.get(k), newValue.get(k)))
                        .toArray(String[]::new);
            var event = AuditEvent.create(
                    tenantId != null ? tenantId : "unknown",
                    "Ifrs17Cohort", c.getId().toString(), c.getName(),
                    action, actorId, actorEmail,
                    oldValue, newValue, changed,
                    UUID.randomUUID().toString()
            );
            return auditPublisher.publish(event);
        });
    }
}

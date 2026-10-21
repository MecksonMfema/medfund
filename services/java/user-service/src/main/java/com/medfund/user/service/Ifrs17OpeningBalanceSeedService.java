package com.medfund.user.service;

import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import com.medfund.user.dto.CreateIfrs17OpeningBalanceSeedRequest;
import com.medfund.user.dto.UpdateIfrs17OpeningBalanceSeedRequest;
import com.medfund.user.entity.Ifrs17OpeningBalanceSeed;
import com.medfund.user.exception.Ifrs17CohortNotFoundException;
import com.medfund.user.exception.Ifrs17OpeningBalanceSeedNotFoundException;
import com.medfund.user.exception.Ifrs17PortfolioNotFoundException;
import com.medfund.user.repository.Ifrs17CohortRepository;
import com.medfund.user.repository.Ifrs17OpeningBalanceSeedRepository;
import com.medfund.user.repository.Ifrs17PortfolioRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 15 §7 (I29) — tenant-admin overrides on auto-derived IFRS 17
 * opening balances. §17 shaping consults this table first; falls back
 * to the auto-derived value when no seed row exists for the (portfolio,
 * cohort, currency, balance_type) tuple as of the report period start.
 *
 * <p>Writes emit an {@link AuditEvent} per Rule 8 with a friendly
 * {@code entityName} (never the UUID, per {@code feedback_audit_entity_name}).
 * Actor identity is captured via {@link com.medfund.shared.audit.AuditActor}
 * on the controller boundary and passed through — never inline JWT
 * extraction (per {@code feedback_audit_actor_email}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Ifrs17OpeningBalanceSeedService {

    static final String AUDIT_ENTITY_TYPE = "Ifrs17OpeningBalanceSeed";

    private final Ifrs17OpeningBalanceSeedRepository repository;
    private final Ifrs17CohortRepository cohortRepository;
    private final Ifrs17PortfolioRepository portfolioRepository;
    private final R2dbcEntityTemplate r2dbcTemplate;
    private final AuditPublisher auditPublisher;

    public Flux<Ifrs17OpeningBalanceSeed> findAll() {
        return repository.findAllOrdered();
    }

    public Mono<Ifrs17OpeningBalanceSeed> findById(UUID id) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new Ifrs17OpeningBalanceSeedNotFoundException(id)));
    }

    /**
     * Lookup consulted by §17 shaping. Returns the latest seed row on-or-before
     * {@code asOf}; empty when no override exists (caller falls back to
     * auto-derived).
     */
    public Mono<Ifrs17OpeningBalanceSeed> findLatestFor(UUID portfolioId, UUID cohortId,
                                                        String currency, String balanceType,
                                                        LocalDate asOf) {
        return repository.findLatestFor(portfolioId, cohortId, currency, balanceType, asOf);
    }

    @Transactional
    public Mono<Ifrs17OpeningBalanceSeed> create(CreateIfrs17OpeningBalanceSeedRequest request,
                                                 String actorId, String actorEmail) {
        return portfolioRepository.findById(request.portfolioId())
                .switchIfEmpty(Mono.error(new Ifrs17PortfolioNotFoundException(request.portfolioId())))
                .then(cohortRepository.findById(request.cohortId())
                        .switchIfEmpty(Mono.error(new Ifrs17CohortNotFoundException(request.cohortId()))))
                .flatMap(cohort -> {
                    if (!cohort.getPortfolioId().equals(request.portfolioId())) {
                        return Mono.<Ifrs17OpeningBalanceSeed>error(new ResponseStatusException(
                                HttpStatus.BAD_REQUEST,
                                "Cohort " + request.cohortId() + " does not belong to portfolio "
                                        + request.portfolioId()));
                    }
                    var seed = new Ifrs17OpeningBalanceSeed();
                    // Leave id null per bug_r2dbc_pre_populated_id_update_mode.
                    seed.setPortfolioId(request.portfolioId());
                    seed.setCohortId(request.cohortId());
                    seed.setCurrency(request.currency().toUpperCase());
                    seed.setBalanceType(request.balanceType());
                    seed.setAmount(request.amount());
                    seed.setEffectiveFrom(request.effectiveFrom());
                    seed.setReasonNote(request.reasonNote());
                    seed.setActorId(actorId != null ? UUID.fromString(actorId) : null);
                    seed.setActorEmail(actorEmail);
                    return r2dbcTemplate.insert(seed);
                })
                .onErrorMap(DataIntegrityViolationException.class, ex -> new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "A seed with (portfolio, cohort, currency, balance_type, effective_from) already exists"))
                .flatMap(saved -> publishAudit(saved, "CREATE", null, actorId, actorEmail)
                        .thenReturn(saved));
    }

    @Transactional
    public Mono<Ifrs17OpeningBalanceSeed> update(UUID id, UpdateIfrs17OpeningBalanceSeedRequest request,
                                                 String actorId, String actorEmail) {
        return findById(id).flatMap(existing -> {
            Map<String, Object> oldValue = snapshot(existing);
            existing.setAmount(request.amount());
            existing.setReasonNote(request.reasonNote());
            if (actorId != null) existing.setActorId(UUID.fromString(actorId));
            existing.setActorEmail(actorEmail);
            return repository.save(existing)
                    .flatMap(saved -> publishAudit(saved, "UPDATE", oldValue, actorId, actorEmail)
                            .thenReturn(saved));
        });
    }

    @Transactional
    public Mono<Void> delete(UUID id, String actorId, String actorEmail) {
        return findById(id).flatMap(existing -> {
            Map<String, Object> oldValue = snapshot(existing);
            return repository.deleteById(id)
                    .then(publishAudit(existing, "DELETE", oldValue, actorId, actorEmail));
        });
    }

    private Map<String, Object> snapshot(Ifrs17OpeningBalanceSeed s) {
        Map<String, Object> m = new HashMap<>();
        m.put("portfolioId", s.getPortfolioId());
        m.put("cohortId", s.getCohortId());
        m.put("currency", s.getCurrency());
        m.put("balanceType", s.getBalanceType());
        m.put("amount", s.getAmount());
        m.put("effectiveFrom", s.getEffectiveFrom());
        m.put("reasonNote", s.getReasonNote());
        return m;
    }

    private Mono<Void> publishAudit(Ifrs17OpeningBalanceSeed s, String action,
                                    Map<String, Object> oldValue,
                                    String actorId, String actorEmail) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            Map<String, Object> newValue = snapshot(s);
            String[] changed = oldValue == null
                    ? new String[]{"portfolioId", "cohortId", "currency", "balanceType",
                                   "amount", "effectiveFrom"}
                    : oldValue.keySet().stream()
                            .filter(k -> !java.util.Objects.equals(oldValue.get(k), newValue.get(k)))
                            .toArray(String[]::new);
            // Friendly entityName per feedback_audit_entity_name: never the UUID.
            String friendlyName = s.getBalanceType() + " " + s.getAmount() + " " + s.getCurrency()
                    + " @ " + s.getEffectiveFrom();
            var event = AuditEvent.create(
                    tenantId != null ? tenantId : "unknown",
                    AUDIT_ENTITY_TYPE,
                    s.getId().toString(),
                    friendlyName,
                    action,
                    actorId,
                    actorEmail,
                    oldValue,
                    newValue,
                    changed,
                    UUID.randomUUID().toString()
            );
            return auditPublisher.publish(event);
        });
    }
}

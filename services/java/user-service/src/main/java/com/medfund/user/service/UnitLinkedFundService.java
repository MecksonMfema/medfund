package com.medfund.user.service;

import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import com.medfund.user.dto.CreateUnitLinkedFundRequest;
import com.medfund.user.dto.UpdateUnitLinkedFundRequest;
import com.medfund.user.entity.UnitLinkedFund;
import com.medfund.user.exception.UnitLinkedFundNotFoundException;
import com.medfund.user.repository.UnitLinkedFundRepository;
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

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Phase 15 §8 (I4) — VFA unit-linked fund catalog. §16 VFA compute reads the
 * fund + its NAV history + variable fee schedule for direct-participation
 * contracts under IFRS 17.71.
 *
 * <p>Every mutation emits an {@link AuditEvent} per Rule 8 with a friendly
 * {@code entityName} (fund name, never the UUID, per
 * {@code feedback_audit_entity_name}). Actor identity flows via
 * {@link com.medfund.shared.audit.AuditActor} on the controller boundary.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UnitLinkedFundService {

    static final String AUDIT_ENTITY_TYPE = "UnitLinkedFund";

    private final UnitLinkedFundRepository repository;
    private final R2dbcEntityTemplate r2dbcTemplate;
    private final AuditPublisher auditPublisher;

    public Flux<UnitLinkedFund> findAll() {
        return repository.findAllOrdered();
    }

    public Flux<UnitLinkedFund> findActive() {
        return repository.findActive();
    }

    public Mono<UnitLinkedFund> findById(UUID id) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new UnitLinkedFundNotFoundException(id)));
    }

    @Transactional
    public Mono<UnitLinkedFund> create(CreateUnitLinkedFundRequest request,
                                       String actorId, String actorEmail) {
        var fund = new UnitLinkedFund();
        // Leave id null per bug_r2dbc_pre_populated_id_update_mode.
        fund.setName(request.name());
        fund.setCurrency(request.currency().toUpperCase());
        fund.setBaseAssetClass(request.baseAssetClass());
        fund.setIsActive(Boolean.TRUE);
        fund.setActorId(actorId != null ? UUID.fromString(actorId) : null);
        fund.setActorEmail(actorEmail);

        return r2dbcTemplate.insert(fund)
                .onErrorMap(DataIntegrityViolationException.class, ex -> new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "A fund with name '" + request.name() + "' already exists"))
                .flatMap(saved -> publishAudit(saved, "CREATE", null, actorId, actorEmail)
                        .thenReturn(saved));
    }

    @Transactional
    public Mono<UnitLinkedFund> update(UUID id, UpdateUnitLinkedFundRequest request,
                                       String actorId, String actorEmail) {
        return findById(id).flatMap(existing -> {
            Map<String, Object> oldValue = snapshot(existing);
            existing.setName(request.name());
            existing.setBaseAssetClass(request.baseAssetClass());
            existing.setIsActive(request.isActive());
            existing.setUpdatedAt(Instant.now());
            if (actorId != null) existing.setActorId(UUID.fromString(actorId));
            existing.setActorEmail(actorEmail);
            return repository.save(existing)
                    .onErrorMap(DataIntegrityViolationException.class, ex -> new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "A fund with name '" + request.name() + "' already exists"))
                    .flatMap(saved -> publishAudit(saved, "UPDATE", oldValue, actorId, actorEmail)
                            .thenReturn(saved));
        });
    }

    @Transactional
    public Mono<Void> delete(UUID id, String actorId, String actorEmail) {
        return findById(id).flatMap(existing -> {
            Map<String, Object> oldValue = snapshot(existing);
            return repository.deleteById(id)
                    .onErrorMap(DataIntegrityViolationException.class, ex -> new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Fund " + id + " is referenced by NAV history, ledger rows, or fee schedules - deactivate instead"))
                    .then(publishAudit(existing, "DELETE", oldValue, actorId, actorEmail));
        });
    }

    private Map<String, Object> snapshot(UnitLinkedFund f) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", f.getName());
        m.put("currency", f.getCurrency());
        m.put("baseAssetClass", f.getBaseAssetClass());
        m.put("isActive", f.getIsActive());
        return m;
    }

    private Mono<Void> publishAudit(UnitLinkedFund f, String action,
                                    Map<String, Object> oldValue,
                                    String actorId, String actorEmail) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            Map<String, Object> newValue = snapshot(f);
            String[] changed = oldValue == null
                    ? new String[]{"name", "currency", "baseAssetClass", "isActive"}
                    : oldValue.keySet().stream()
                            .filter(k -> !Objects.equals(oldValue.get(k), newValue.get(k)))
                            .toArray(String[]::new);
            // Friendly entityName per feedback_audit_entity_name — the fund name, never UUID.
            String friendlyName = f.getName() + " (" + f.getCurrency() + ")";
            var event = AuditEvent.create(
                    tenantId != null ? tenantId : "unknown",
                    AUDIT_ENTITY_TYPE,
                    f.getId().toString(),
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
